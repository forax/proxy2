import static com.github.forax.proxy2.MethodBuilder.methodBuilder;
import static com.github.forax.proxy2.Proxy2.createAnonymousProxyFactory;
import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;

import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public class Main2 {
  public interface MyIntBinaryOp {
    int applyAsInt(int left, int right);
  }
  
  private static final ClassValue<MethodHandle> FACTORY_CACHE = 
      new ClassValue<MethodHandle>() {
        @Override
        protected MethodHandle computeValue(final Class<?> type) {
          return createAnonymousProxyFactory(publicLookup(), methodType(type, type), 
              new ProxyHandler.Default() {
                @Override
                public boolean override(Method method) { return true; }
                
                @Override
                public CallSite bootstrap(ProxyContext context) throws Throwable {
                  Method method = context.method();
                  MethodHandle target = methodBuilder(context.type())
                      .dropFirstParameter()  // drop the proxy object
                      .thenCall(MethodHandles.publicLookup(), method);
                  return new ConstantCallSite(target);
                }
              });
        }
      };
  
  private static <T> T proxy(T delegate, Class<T> interfaze) {
    /*if (!Modifier.isPublic(interfaze.getModifiers())) {
      throw new IllegalArgumentException(interfaze + " should be public");
    }*/
    try {
      return interfaze.cast(FACTORY_CACHE.get(interfaze).invoke(delegate));
    } catch(RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }
  
  private static int COUNTER;
  private static /* non final */ MyIntBinaryOp op;
  
  private static void foo() {
    COUNTER += op.applyAsInt(2, 3);
  }
  
  public static void main(String[] args) {
    MyIntBinaryOp delegate = new MyIntBinaryOp() {
      @Override
      public int applyAsInt(int left, int right) {
        return left + right;
      }
    };
    MyIntBinaryOp op = proxy(delegate, MyIntBinaryOp.class);
    System.out.println(op.applyAsInt(2, 3));
    
    Main2.op = op;
    for(int i = 0; i < 1_000_000; i++) {
      foo();
    }
    System.out.println(COUNTER);
  }
}
