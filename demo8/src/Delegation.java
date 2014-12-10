import static com.github.forax.proxy2.MethodBuilder.methodBuilder;
import static java.lang.invoke.MethodHandles.publicLookup;

import java.io.PrintStream;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;

import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyFactory;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public interface Delegation {
  public void display(String message);
  
  public static void main(String[] args) {
    ProxyFactory<Delegation> factory = Proxy2.createAnonymousProxyFactory(Delegation.class, new Class<?>[] { PrintStream.class },
        new ProxyHandler.Default() { 
          @Override
          public CallSite bootstrap(ProxyContext context) throws Throwable {
            MethodHandle target =
              methodBuilder(context.type())
                .dropFirstParameter()
                .thenCall(publicLookup(), PrintStream.class.getMethod("println", String.class));
            return new ConstantCallSite(target);
          }
        });
    
    Delegation hello = factory.create(System.out);
    hello.display("hello proxy2");
  }
}
