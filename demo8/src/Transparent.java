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

public interface Transparent {
  public static void main(String[] args) {
    ProxyFactory<IntBinaryOperator> factory = Proxy2.createAnonymousProxyFactory(IntBinaryOperator.class, new Class<?>[] { IntBinaryOperator.class },
        new ProxyHandler.Default() { 
          @Override
          public CallSite bootstrap(ProxyContext context) throws Throwable {
            MethodHandle target =
              methodBuilder(context.type())
                .dropFirst()
                .unreflect(publicLookup(), context.method());
            return new ConstantCallSite(target);
          }
        });
    
    IntBinaryOperator op = (a, b) -> a + b;
    
    IntBinaryOperator op2 = factory.create(op);
    System.out.println(op2.applyAsInt(1, 2));
  }
}
