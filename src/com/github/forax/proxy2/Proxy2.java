package com.github.forax.proxy2;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_7;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import sun.misc.Unsafe;

/**
 * A bunch of static factory methods to create proxy factories.
 * 
 * Unlike java.lang.reflect.Proxy, the implementation doesn't do any caching,
 * so calling {@link #createAnonymousProxyFactory(Lookup, MethodType, ProxyHandler)}
 * with the same interface as return type of the method type will generated
 * as many proxy classes as the number of calls.
 */
public class Proxy2 {
  private Proxy2() {
    // no instance
  }
  
  /**
   * Specify how to link a proxy method to its implementation. 
   */
  public interface ProxyHandler {
    /**
     * Returns true if the method should be overridden by the proxy.
     * This method is only called for method that have an existing implementation
     * (default methods or Object's toString(), equals() and hashCode().
     * This method is called once by method when generating the proxy call.
     * 
     * @param method a method of the interface that may be overridden
     * @return true if the method should be overridden by the proxy.
     */
    public boolean override(Method method);
    
    /**
     * Called to link a proxy method to a target method handle (through a callsite's target).
     * This method is called once by method at runtime the first time the proxy method is called.
     * 
     * @param lookup lookup object corresponding to the interface of the proxy.
     * @param method the method object to link.
     * @return a callsite object indicating how to link the method to a target method handle.
     * @throws Throwable if any errors occur.
     */
    public CallSite bootstrap(Lookup lookup, Method method) throws Throwable;
  }

  /**
   * A factory of proxy implementing an interface.
   * 
   * @param <T> the type of the proxy interface.
   * @see Proxy2#createAnonymousProxyFactory(Class, Class[], ProxyHandler)
   */
  @FunctionalInterface
  public interface ProxyFactory<T> {
    /**
     * Create a proxy with a value for each field of the proxy.
     * @param fieldValues the value of each field of the proxy. 
     * @return a new proxy instance.
     */
    public T create(Object... fieldValues);
  }
  
  private static final Class<?>[] EMPTY_FIELD_TYPES = new Class<?>[0];

  /**
   * Create a factory that will create anonymous proxy instances implementing an interface {@code type} and no field.
   * The {@code handler} is used to specify the linking between a method and its implementation.
   * The created proxy class will define no field so {@link ProxyFactory#create(Object...)} should be called with no argument.
   * 
   * @param type the interface that the proxy should respect.
   * @param handler an interface that specifies how a proxy method is linked to its implementation.
   * @return a proxy factory that will create proxy instance.
   * 
   * @see #createAnonymousProxyFactory(Class, Class[], ProxyHandler)
   */
  public static <T> ProxyFactory<T> createAnonymousProxyFactory(Class<? extends T> type, ProxyHandler handler) {
    return createAnonymousProxyFactory(type, EMPTY_FIELD_TYPES, handler);
  }

  /**
   * Create a factory that will create anonymous proxy instances implementing an interface {@code type}
   * and with several fields described by {@code fieldTypes}.
   * The {@code handler} is used to specify the linking between a method and its implementation.
   * The created proxy class will define several fields so {@link ProxyFactory#create(Object...)} should be called with
   * the values of the field as argument.
   * 
   * @param type the interface that the proxy should respect.
   * @param fieldTypes type of the fields of the generated proxy.
   * @param handler an interface that specifies how a proxy method is linked to its implementation.
   * @return a proxy factory that will create proxy instance.
   * 
   * @see #createAnonymousProxyFactory(Lookup, MethodType, ProxyHandler)
   * @see #createAnonymousProxyFactory(Class, ProxyHandler)
   */
  public static <T> ProxyFactory<T> createAnonymousProxyFactory(Class<? extends T> type, Class<?>[] fieldTypes, ProxyHandler handler) {
    MethodHandle mh = createAnonymousProxyFactory(MethodHandles.publicLookup(), MethodType.methodType(type, fieldTypes), handler);
    return new ProxyFactory<T>() {   // don't use a lambda here to avoid cycle when retro-weaving
      @Override
      public T create(Object... fieldValues) {
        try {
          return type.cast(mh.invokeWithArguments(fieldValues));
        } catch (Throwable e) {
          if (e instanceof RuntimeException) {
            throw (RuntimeException)e;
          }
          if (e instanceof Error) {
            throw (Error)e;
          }
          throw new UndeclaredThrowableException(e);
        }
      }
    };
  }

  private static final Unsafe UNSAFE;
  static {
    Unsafe unsafe;
    try {
      Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      unsafe =  (Unsafe)unsafeField.get(null);
    } catch (NoSuchFieldException|IllegalAccessException e) {
      throw new AssertionError(e);
    }
    UNSAFE = unsafe;
  }
  
  private static String internalName(Class<?> type) {
    return type.getName().replace('.', '/');
  }
  
  private static String[] internalNames(Class<?>[] types) {
    // keep it compatible with Java 7
    //return Arrays.stream(method.getExceptionTypes()).map(Proxy2::internalName).toArray(String[]::new);
    String[] array = new String[types.length];
    for(int i = 0; i < array.length; i++) {
      array[i] = internalName(types[i]);
    }
    return array;
  }
  
  //private static final String PROXY_NAME = "com/github/forax/proxy2/Foo";
  private static final String PROXY_NAME = "java/lang/invoke/Foo";
  
  /**
   * Create a factory that will create anonymous proxy instances with several fields described by
   * the parameter types of {@code methodType} and implementing an interface described by
   * the return type of {@code methodType}.
   * The {@code handler} is used to specify the linking between a method and its implementation.
   * The returned {@link MethodHandle} will have its type being equals to the {@code methodType}
   * taken as argument.
   * 
   * @param lookup access token used to access to the interface methods, this object will be passed
   *               as first parameter of the proxy {@code handler}.
   * @param methodType the parameter types of this {@link MethodType} described the type of the fields
   *                   and the return type the interface implemented by the proxy. 
   * @param handler an interface that specifies how a proxy method is linked to its implementation.
   * @return a method handle that if {@link MethodHandle#invokeExact(Object...) called} will create
   *         a proxy instance of a class implementing the return interfaces. 
   * 
   * @see #createAnonymousProxyFactory(Class, Class[], ProxyHandler)
   */
  public static MethodHandle createAnonymousProxyFactory(Lookup lookup, MethodType methodType, ProxyHandler handler) {
    Class<?> interfaze = methodType.returnType();
    if (lookup.in(interfaze).lookupModes() == 0) {
      throw new UnsupportedOperationException("interface " + interfaze + " is not visible from " + lookup);
    }

    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
    writer.visit(V1_7, ACC_PUBLIC|ACC_SUPER|ACC_FINAL, PROXY_NAME, null, "java/lang/Object", new String[]{ internalName(interfaze) });

    String initDesc;
    {
      initDesc = methodType.changeReturnType(void.class).toMethodDescriptorString();
      MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", initDesc, null, null);
      String factoryDesc = methodType.toMethodDescriptorString();
      MethodVisitor factory = writer.visitMethod(ACC_PUBLIC|ACC_STATIC, "0-^-0", factoryDesc, null, null);
      init.visitCode();
      init.visitVarInsn(ALOAD, 0);
      init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      factory.visitCode();
      factory.visitTypeInsn(NEW, PROXY_NAME);
      factory.visitInsn(DUP);

      int slot = 1;
      for(int i = 0; i < methodType.parameterCount(); i++) {
        Class<?> boundType = methodType.parameterType(i);
        String fieldName = "arg" + i;
        FieldVisitor fv = writer.visitField(ACC_PRIVATE|ACC_FINAL, fieldName, Type.getDescriptor(boundType), null, null);
        fv.visitEnd();

        int loadOp = Type.getType(boundType).getOpcode(ILOAD);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(loadOp, slot);
        init.visitFieldInsn(PUTFIELD, PROXY_NAME, fieldName, Type.getDescriptor(boundType));

        factory.visitVarInsn(loadOp, slot - 1);

        slot += (boundType == long.class || boundType == double.class)? 2: 1;
      }

      init.visitInsn(RETURN);
      factory.visitMethodInsn(INVOKESPECIAL, PROXY_NAME, "<init>", initDesc, false);
      factory.visitInsn(ARETURN);

      init.visitMaxs(-1, -1);
      init.visitEnd();
      factory.visitMaxs(-1, -1);
      factory.visitEnd();
    }

    String lookupPlaceHolder = "<<LOOKUP_HOLDER>>";
    int lookupHolderCPIndex = writer.newConst(lookupPlaceHolder);
    String handlerPlaceHolder = "<<HANDLER_HOLDER>>";
    int handlerHolderCPIndex = writer.newConst(handlerPlaceHolder);

    Method[] methods = interfaze.getMethods();
    int[] methodHolderCPIndexes = new int[methods.length];
    for(int methodIndex = 0; methodIndex < methods.length; methodIndex++) {
      Method method = methods[methodIndex];
      if (Modifier.isStatic(method.getModifiers())) {
        continue;
      }
      if (!handler.override(method)) {
        continue;
      }
      String methodDesc = Type.getMethodDescriptor(method);
      MethodVisitor mv = writer.visitMethod(ACC_PUBLIC, method.getName(), methodDesc, null,
          internalNames(method.getExceptionTypes()));
      mv.visitAnnotation("Ljava/lang/invoke/LambdaForm$Hidden;", true);
      mv.visitAnnotation("Ljava/lang/invoke/ForceInline;", true);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 0);
      for(int i = 0; i < methodType.parameterCount(); i++) {
        Class<?> fieldType = methodType.parameterType(i);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, PROXY_NAME, "arg" + i, Type.getDescriptor(fieldType));
      }
      int slot = 1;
      for(Class<?> parameterType: method.getParameterTypes()) {
        mv.visitVarInsn(Type.getType(parameterType).getOpcode(ILOAD), slot);
        slot += (parameterType == long.class || parameterType == double.class)? 2: 1;
      }
      String methodPlaceHolder = "<<METHOD_HOLDER " + methodIndex + ">>";
      methodHolderCPIndexes[methodIndex] = writer.newConst(methodPlaceHolder);
      mv.visitInvokeDynamicInsn(method.getName(),
          "(Ljava/lang/Object;" + initDesc.substring(1, initDesc.length() - 2) + methodDesc.substring(1),
          BSM, handlerPlaceHolder, lookupPlaceHolder, methodPlaceHolder);
      mv.visitInsn(Type.getReturnType(method).getOpcode(IRETURN));
      mv.visitMaxs(-1, -1);
      mv.visitEnd();
    }
    writer.visitEnd();
    byte[] data = writer.toByteArray();

    int constantPoolSize = writer.newConst("<<SENTINEL>>");

    Object[] patches = new Object[constantPoolSize];
    patches[lookupHolderCPIndex] = lookup;
    patches[handlerHolderCPIndex] = handler;
    for(int i = 0; i < methodHolderCPIndexes.length; i++) {
      patches[methodHolderCPIndexes[i]] = methods[i];
    }
    Class<?> clazz = UNSAFE.defineAnonymousClass(interfaze /*Proxy2.class*/, data, patches);
    UNSAFE.ensureClassInitialized(clazz);
    try {
      return MethodHandles.publicLookup().findStatic(clazz, "0-^-0", methodType);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  private static final Handle BSM =
      new Handle(H_INVOKESTATIC, internalName(Proxy2.class), "bootstrap",
          MethodType.methodType(CallSite.class, Lookup.class, String.class, MethodType.class, ProxyHandler.class, Lookup.class, Method.class).toMethodDescriptorString());

  /**
   * Internal Entry point, should never called directly.
   */
  public static CallSite bootstrap(Lookup lookup, String name, MethodType methodType, ProxyHandler handler, Lookup interfaceLookup, Method method) throws Throwable {
    return handler.bootstrap(interfaceLookup, method);
  }
}
