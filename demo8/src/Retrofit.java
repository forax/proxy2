import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.function.IntUnaryOperator;

import com.github.forax.proxy2.MethodBuilder;
import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyHandler;


/**
 * Emulate the java.lang.reflect.Proxy API using the Proxy2 API.
 */
public class Retrofit {
  private static ClassValue<MethodHandle> CACHE = new ClassValue<MethodHandle>() {
    @Override
    protected MethodHandle computeValue(Class<?> type) {
      MethodHandle mh = Proxy2.createAnonymousProxyFactory(MethodType.methodType(type, InvocationHandler.class), new ProxyHandler() {
        @Override
        public boolean override(Method method) {
          return true;
        }
        
        @Override
        public boolean isMutable(int fieldIndex, Class<?> fieldType) {
          return false;
        }
        
        @Override
        public CallSite bootstrap(ProxyContext context) throws Throwable {
          Method method = context.method();
          /*
          return new ConstantCallSite(
              MethodHandles.insertArguments(INVOCATIONHANDLER_INVOKE, 2, method)
                  .asCollector(Object[].class, method.getParameterCount())
                  .asType(context.type()));
          */
          return new ConstantCallSite(
              MethodBuilder.methodBuilder(context.type())
                .insertValueAt(2, Method.class, method)
                .boxLastArguments(method.getParameterCount())
                .convertReturnTypeTo(Object.class)
                .thenCallMethodHandle(INVOCATIONHANDLER_INVOKE));
        }
      });
      return mh.asType(MethodType.methodType(Object.class, InvocationHandler.class));
    }
  };
  
  final static MethodHandle INVOCATIONHANDLER_INVOKE;
  static {
    MethodHandle mh;
    try {
      mh = MethodHandles.publicLookup().findVirtual(InvocationHandler.class, "invoke",
          MethodType.methodType(Object.class, Object.class, Method.class, Object[].class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
    INVOCATIONHANDLER_INVOKE = MethodHandles.permuteArguments(mh,
        MethodType.methodType(Object.class, Object.class, InvocationHandler.class, Method.class, Object[].class),
        new int[]{ 1, 0, 2, 3 });
  }
  
  public static <T> T newProxyInstance(Class<T> type, InvocationHandler invocationHandler) {
    try {
      return type.cast(CACHE.get(type).invokeExact(invocationHandler));
    } catch(RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }
  
  public static void main(String[] args) {
    IntUnaryOperator op = newProxyInstance(IntUnaryOperator.class,
        (Object proxy, Method method, Object[] arguments) -> {
          System.out.println(Arrays.toString(arguments));
          return arguments[0];
        });
    System.out.println(op.applyAsInt(10));
  }
}
