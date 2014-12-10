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

public interface Hello {
  public void display(String message);
  
  public static void main(String[] args) {
    ProxyFactory<Hello> factory = Proxy2.createAnonymousProxyFactory(Hello.class, new ProxyHandler.Default() { 
      @Override
      public CallSite bootstrap(ProxyContext context) throws Throwable {
        MethodHandle target =
            methodBuilder(context.type())
              .dropFirstParameter()
              .insertValueAt(0, PrintStream.class, System.out)
              .thenCall(publicLookup(), PrintStream.class.getMethod("println", String.class));
        return new ConstantCallSite(target);
      }
    });
    
    Hello hello = factory.create();
    hello.display("hello proxy2");
  }
}
