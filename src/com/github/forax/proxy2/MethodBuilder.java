package com.github.forax.proxy2;

import static java.lang.invoke.MethodHandles.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;

public class MethodBuilder {
  private MethodType sig;
  private MHTransformer transformer;
  
  @FunctionalInterface
  public /*FIXME*/interface MHTransformer {
     MethodHandle transform(MethodHandle mh) throws NoSuchFieldException, IllegalAccessException;
  }
  
  @FunctionalInterface
  public interface Fun<T, R> {
    public R apply(T argument) throws NoSuchFieldException, IllegalAccessException;
  }
  
  private MethodBuilder() {
    // use factory
  }

  private MethodBuilder apply(MethodType sig, MHTransformer transformer) {
    this.sig = sig;
    this.transformer = transformer;
    return this;
  }
  
  public static MethodBuilder methodBuilder(MethodType methodType) {
    return new MethodBuilder().apply(methodType, mh -> mh);
  }
  public static MethodBuilder methodBuilder(Method method, Class<?>... fieldTypes) {
    ArrayList<Class<?>> parameterTypes = new ArrayList<>();
    parameterTypes.add(Object.class);                                // prepend the proxy object type
    Collections.addAll(parameterTypes, fieldTypes);                  // then the field types
    Collections.addAll(parameterTypes, method.getParameterTypes());  // then the parameter types
    return methodBuilder(MethodType.methodType(method.getReturnType(), parameterTypes));
  }
  
  public MethodBuilder boxAllArguments() {
    MethodType sig = this.sig;
    MHTransformer transformer = this.transformer;
    return apply(MethodType.methodType(sig.returnType(), Object[].class), mh -> transformer.transform(mh.asCollector(Object[].class, sig.parameterCount()).asType(sig)));
  }
  public MethodBuilder insertValueAt(int parameterIndex, Class<?> type, Object value) {
    MHTransformer transformer = this.transformer;
    return apply(sig.insertParameterTypes(parameterIndex, type), mh -> transformer.transform(insertArguments(mh, parameterIndex, value)));
  }
  public MethodBuilder dropFirstParameter() {
    return dropParameterAt(0);
  }
  public MethodBuilder dropParameterAt(int parameterIndex) {
    Class<?> type = sig.parameterType(parameterIndex);
    MHTransformer transformer = this.transformer;
    return apply(sig.dropParameterTypes(parameterIndex, parameterIndex + 1), mh -> transformer.transform(dropArguments(mh, parameterIndex, type)));
  }
  public MethodBuilder convertTo(MethodType methodType) {
    MHTransformer transformer = this.transformer;
    return apply(methodType, mh -> transformer.transform(mh.asType(methodType)));
  }
  public MethodBuilder convertTo(Class<?> returnType, Class<?>... parameterTypes) {
    return convertTo(MethodType.methodType(returnType, parameterTypes));
  }
  
  public MethodBuilder before(Fun<? super MethodBuilder, ? extends MethodHandle> function) {
    MethodType instrType = sig.changeReturnType(void.class);
    MHTransformer transformer = this.transformer;
    return apply(sig, mh -> transformer.transform(foldArguments(mh, function.apply(methodBuilder(instrType)))));
  }
  public MethodBuilder after(Fun<? super MethodBuilder, ? extends MethodHandle> function) {
    Class<?> returnType = sig.returnType();
    MethodType instrType = (returnType == void.class)?sig: sig.insertParameterTypes(0, returnType);
    MHTransformer transformer = this.transformer;
    return apply(sig, mh -> transformer.transform(foldArguments(function.apply(methodBuilder(instrType)), mh)));
  }
  public MethodBuilder trap(Class<? extends Throwable> exceptionType, Fun<? super MethodBuilder, ? extends MethodHandle> function) {
    MethodType instrType = sig.insertParameterTypes(0, exceptionType);
    MHTransformer transformer = this.transformer;
    return apply(sig, mh -> transformer.transform(catchException(mh, exceptionType, function.apply(methodBuilder(instrType)))));
  }
  
  public MethodHandle thenCallMethodHandle(MethodHandle target) throws NoSuchFieldException, IllegalAccessException {
    MethodType targetType = target.type();
    if (!targetType.equals(sig)) {
      throw new WrongMethodTypeException("target type " + targetType + " is not equals to current type " + sig);
    }
    return transformer.transform(target);
  }
  public MethodHandle thenCall(Lookup lookup, Method method) throws NoSuchFieldException, IllegalAccessException {
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
  public MethodHandle thenCallIdentity() throws NoSuchFieldException, IllegalAccessException {
    return thenCallMethodHandle(identity(sig.parameterType(0)));
  }
  public MethodHandle thenCallAndReturnAConstant(Object value) throws NoSuchFieldException, IllegalAccessException {
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