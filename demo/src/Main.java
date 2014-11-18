import static com.github.forax.proxy2.MethodBuilder.methodBuilder;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;

import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyFactory;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public class Main {
  private static int COUNTER;
  private static MyIntUnaryOp op;
  
  public interface MyIntUnaryOp {
    public int applyAsInt(int value);
  }
  
  private static void foo() {
    COUNTER += op.applyAsInt(3);
  }
  
  public static void main(String[] args) throws Throwable {
    ProxyFactory<MyIntUnaryOp> factory =
      Proxy2.createAnonymousProxyFactory(MyIntUnaryOp.class, 
        new ProxyHandler() {
          @Override
          public boolean override(Method method) { return true; }

          @Override
          public CallSite bootstrap(Lookup lookup, Method method) throws Throwable {
            System.out.println("require implementation of " + method);
            
            MethodHandle target = methodBuilder(method)
                .dropFirstParameter()  // drop the proxy object
                .thenCallIdentity();   // then return the value taken as argument
            return new ConstantCallSite(target);
          }
        });
    MyIntUnaryOp op = factory.create();
    System.out.println(op.applyAsInt(3));
    
    Main.op = op;
    for(int i = 0; i < 1_000_000; i++) {
      foo();
    }
    System.out.println(COUNTER);
  }
}
