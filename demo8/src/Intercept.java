import static com.github.forax.proxy2.MethodBuilder.methodBuilder;
import static java.lang.invoke.MethodHandles.publicLookup;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.util.function.IntBinaryOperator;

import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyFactory;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public interface Intercept {
  public static void intercept(int v1, int v2) {
    //System.out.println("intercepted " + v1 + " " + v2);
    throw null;
  }
  
  public static void main(String[] args) {
    ProxyFactory<IntBinaryOperator> factory = Proxy2.createAnonymousProxyFactory(IntBinaryOperator.class, new Class<?>[] { IntBinaryOperator.class },
        new ProxyHandler.Default() { 
          @Override
          public CallSite bootstrap(ProxyContext context) throws Throwable {
            MethodHandle target =
              methodBuilder(context.type())
                .dropFirstParameter()
                .before(b -> b
                    .dropFirstParameter()
                    .thenCall(publicLookup(), Intercept.class.getMethod("intercept", int.class, int.class)))
                .thenCall(publicLookup(), context.method());
            return new ConstantCallSite(target);
          }
        });
    
    IntBinaryOperator op = (a, b) -> a + b;
    
    IntBinaryOperator op2 = factory.create(op);
    System.out.println(op2.applyAsInt(1, 2));
  }
}
