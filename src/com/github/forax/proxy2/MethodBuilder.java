package com.github.forax.proxy2;

import static java.lang.invoke.MethodHandles.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import com.github.forax.proxy2.Proxy2.ProxyContext;

/**
 * A builder-like class easing the creation of method handle-tree to specify
 * implementation of a method.
 */
public class MethodBuilder {
  private MethodType sig;
  private MHTransformer transformer;
  
  @FunctionalInterface
  interface MHTransformer {
     MethodHandle transform(MethodHandle mh) throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException;
  }
  
  /**
   * A function like {@link java.util.function.Function} but that allows to propagate reflective exception.
   * @param <T> the type of the argument.
   * @param <R> the type of the return value.
   */
  @FunctionalInterface
  public interface Fun<T, R> {
    /**
     * Call the function.
     * @param argument the argument of the function
     * @return the return 
     * @throws NoSuchMethodException throws is a method is not visible.
     * @throws NoSuchFieldException throws if a field is not visible.
     * @throws IllegalAccessException throws if a type or a member of a type is not visible.
     */
    public R apply(T argument) throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException;
  }
  
  private MethodBuilder() {
    // use factory
  }

  private MethodBuilder apply(MethodType sig, MHTransformer transformer) {
    this.sig = sig;
    this.transformer = transformer;
    return this;
  }
  
  /**
   * Create a method builder with the signature of the implementation method should conform.
   * Typically, the MethodBuilder can be initialized using the {@link ProxyContext#type() type}
   * got from the {@link ProxyContext}.
   * <pre>
   *    public CallSite bootstrap(ProxyContext context) throws Throwable {
   *      MethodBuilder builder = MethodBuilder.methodBuilder(context.type());
   *      ...
   *    }
   * </pre>
   * 
   * @param methodType the method type that the {@link #thenCallMethodHandle(MethodHandle) resulting method handle}.
   * @return a new method builder
   */
  public static MethodBuilder methodBuilder(MethodType methodType) {
    return new MethodBuilder().apply(methodType, mh -> mh);
  }
  
  /**
   * Ask to box all arguments into an array of java.lang.Object.
   * @return the current method builder
   */
  public MethodBuilder boxAllArguments() {
    MethodType sig = this.sig;
    MHTransformer transformer = this.transformer;
    return apply(MethodType.methodType(sig.returnType(), Object[].class), mh -> transformer.transform(mh.asCollector(Object[].class, sig.parameterCount()).asType(sig)));
  }
  
  /**
   * Ask to insert a value at {@code parameterIndex}.
   * @param parameterIndex position of the inserted value in the parameters
   * @param type type of the inserted value
   * @param value the value to insert
   * @return the current method builder
   */
  public <T> MethodBuilder insertValueAt(int parameterIndex, Class<T> type, T value) {
    MHTransformer transformer = this.transformer;
    return apply(sig.insertParameterTypes(parameterIndex, type), mh -> transformer.transform(insertArguments(mh, parameterIndex, value)));
  }
  
  /**
   * Ask to drop the first parameter.
   * @return the current method builder
   * 
   * @see #dropParameterAt(int)
   */
  public MethodBuilder dropFirstParameter() {
    return dropParameterAt(0);
  }
  
  /**
   * Ask to drop the parameter at {@code parameterIndex}
   * @param parameterIndex position of the dropped parameter
   * @return the current method builder
   */
  public MethodBuilder dropParameterAt(int parameterIndex) {
    Class<?> type = sig.parameterType(parameterIndex);
    MHTransformer transformer = this.transformer;
    return apply(sig.dropParameterTypes(parameterIndex, parameterIndex + 1), mh -> transformer.transform(dropArguments(mh, parameterIndex, type)));
  }
  
  /**
   * Ask to convert the parameter and the return value.
   * @param methodType the expected type of the parameter and return type.
   * @return the current method builder
   */
  public MethodBuilder convertTo(MethodType methodType) {
    MHTransformer transformer = this.transformer;
    MethodType sig = this.sig;
    return apply(methodType, mh -> transformer.transform(mh.asType(sig)));
  }
  
  /**
   * Ask to convert the parameter and the return value.
   * @param returnType the expected return type
   * @param parameterTypes the expected parameter types
   * @return the current method builder
   * 
   * @see #convertTo(MethodType)
   */
  public MethodBuilder convertTo(Class<?> returnType, Class<?>... parameterTypes) {
    return convertTo(MethodType.methodType(returnType, parameterTypes));
  }
  
  /**
   * Ask to execute a code specified by a {@link MethodBuilder} before the current code.
   * @param function a function that specify the code to execute before the current code,
   *                 the method handle produced by the method builder must return void.
   * @return the current method builder
   */
  public MethodBuilder before(Fun<? super MethodBuilder, ? extends MethodHandle> function) {
    MethodType instrType = sig.changeReturnType(void.class);
    MHTransformer transformer = this.transformer;
    return apply(sig, mh -> transformer.transform(foldArguments(mh, function.apply(methodBuilder(instrType)))));
  }
  
  /**
   * Ask to execute a code specified by a {@link MethodBuilder} after the current code.
   * @param function a function that specify the code to execute after the current code,
   *                 the method handle produced by the method builder is called with the return value
   *                 of the current code as first parameter followed by the other parameters.
   *                 The return value must be of the same type as the current code.
   * @return the current method builder
   */
  public MethodBuilder after(Fun<? super MethodBuilder, ? extends MethodHandle> function) {
    Class<?> returnType = sig.returnType();
    MethodType instrType = (returnType == void.class)?sig: sig.insertParameterTypes(0, returnType);
    MHTransformer transformer = this.transformer;
    return apply(sig, mh -> transformer.transform(foldArguments(function.apply(methodBuilder(instrType)), mh)));
  }
  
  /**
   * Catch the exception of type {@code exceptionType} and execute the method handle returned as result
   * of the {@code function}.
   * @param exceptionType the type of the exception to catch.
   * @param function a function that specify the code to execute when an exception is thrown.
   *                 The method handle returned by the function must have the same signature as the current code.
   * @return the current method builder
   */
  public MethodBuilder trap(Class<? extends Throwable> exceptionType, Fun<? super MethodBuilder, ? extends MethodHandle> function) {
    MethodType instrType = sig.insertParameterTypes(0, exceptionType);
    MHTransformer transformer = this.transformer;
    return apply(sig, mh -> transformer.transform(catchException(mh, exceptionType, function.apply(methodBuilder(instrType)))));
  }
  
  /**
   * Create a method handle that will apply all transformations specified by the current method builder
   * and then call the {@code target} method handle. 
   * @param target the target method handle.
   * @return a new method handle constructed by applying all transformations on the target method handle.
   * @throws NoSuchMethodException throws is a method is not visible.
   * @throws NoSuchFieldException throws if a field is not visible.
   * @throws IllegalAccessException throws if a type or a member of a type is not visible.
   */
  public MethodHandle thenCallMethodHandle(MethodHandle target) throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
    MethodType targetType = target.type();
    if (!targetType.equals(sig)) {
      throw new WrongMethodTypeException("target type " + targetType + " is not equals to current type " + sig);
    }
    return transformer.transform(target);
  }
  
  /**
   * Create a method handle that will apply all transformations specified by the current method builder
   * and then call the {@code method} method. 
   * This method uses a cache if the method is a virtual method (either on class or interface)
   * 
   * @param lookup the lookup object used to find the @code method}
   * @param method the method called at the end of the transformation.
   * @return a new method handle constructed by applying all transformations on the target method.
   * @throws NoSuchMethodException throws is a method is not visible.
   * @throws NoSuchFieldException throws if a field is not visible.
   * @throws IllegalAccessException throws if a type or a member of a type is not visible.
   */
  public MethodHandle thenCall(Lookup lookup, Method method) throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
    MethodHandle target = lookup.unreflect(method);
    MethodType targetType = target.type();
    if (!targetType.equals(sig)) {
      throw new WrongMethodTypeException("target type " + targetType + " is not equals to current type " + sig);
    }
    
    int modifiers = method.getModifiers();
    if (!Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers)) { // can be virtual
      target = new InliningCacheCallSite(target).dynamicInvoker();
    }
    return thenCallMethodHandle(target);
  }
  
  /**
   * Create a method handle that will apply all transformations specified by the current method builder
   * and then return the value passed as argument.
   * @return a new method handle constructed by applying all transformations on the identity.
   * 
   * @throws NoSuchMethodException throws is a method is not visible.
   * @throws NoSuchFieldException throws if a field is not visible.
   * @throws IllegalAccessException throws if a type or a member of a type is not visible.
   */
  public MethodHandle thenCallIdentity() throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
    return thenCallMethodHandle(identity(sig.parameterType(0)).asType(sig));
  }
  
  /**
   * Create a method handle that will apply all transformations specified by the current method builder
   * and then return the constant value taken as argument of this method.
   * @return a new method handle constructed by applying all transformations on the identity.
   * @throws NoSuchMethodException throws is a method is not visible.
   * @throws NoSuchFieldException throws if a field is not visible.
   * @throws IllegalAccessException throws if a type or a member of a type is not visible.
   */
  public MethodHandle thenCallAndReturnAConstant(Object value) throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
    return thenCallMethodHandle(constant(sig.parameterType(0), value));
  }
  
  static class InliningCacheCallSite extends MutableCallSite {
    private static final int MAX_KIND_OF_TYPE = 8;  // number of kind of type before considering the callsite as megamorphic
    private final static MethodHandle CLASS_CHECK, FALLBACK;
    static {
      Lookup lookup = lookup();
      Class<?> thisClass = lookup.lookupClass();
      try {
        CLASS_CHECK = lookup.findStatic(thisClass, "classCheck", MethodType.methodType(boolean.class, Object.class, Class.class));
        FALLBACK = lookup.findStatic(thisClass, "fallback", MethodType.methodType(MethodHandle.class, thisClass, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError();
      }
    }
       
    private final MethodHandle endPoint;
    private int kindOfTypeCounter;   // the access are racy but we don't care ! 

    InliningCacheCallSite(MethodHandle endPoint) {
      super(endPoint.type());
      this.endPoint = endPoint;
      MethodType type = endPoint.type();
      setTarget(foldArguments(exactInvoker(type), FALLBACK.bindTo(this).asType(MethodType.methodType(MethodHandle.class, type.parameterType(0)))));
    }
    
    @SuppressWarnings("unused")  // used by a method handle
    private static boolean classCheck(Object receiver, Class<?> receiverClass) {
      return receiver.getClass() == receiverClass;
    }
    
    @SuppressWarnings("unused")  // used by a method handle
    private static MethodHandle fallback(InliningCacheCallSite callsite, Object receiver) throws Throwable {
      MethodHandle endPoint = callsite.endPoint;
      if (callsite.kindOfTypeCounter++ == MAX_KIND_OF_TYPE) { // too many kinds of type, just give up
        callsite.setTarget(endPoint);
        return endPoint;
      }
      
      if (receiver == null) { // no receiver
        return endPoint;  // will throw a NPE later 
      }
      Class<?> receiverClass = receiver.getClass();
      
      MethodType endPointType = endPoint.type();
      MethodHandle test = insertArguments(CLASS_CHECK, 1, receiverClass).asType(MethodType.methodType(boolean.class, endPointType.parameterType(0)));
      MethodHandle target = endPoint.asType(endPointType.changeParameterType(0, receiverClass)).asType(endPointType); // insert a cast to help the JIT ?
      MethodHandle gwt = guardWithTest(test, target, callsite.getTarget());
      callsite.setTarget(gwt);
      return endPoint;
    }
  }
}