import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;

import java.io.PrintStream;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;

import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyFactory;
import com.github.forax.proxy2.Proxy2.ProxyHandler;


public class GCTest {
  public static void main(String[] args) {
    ProxyFactory<Runnable> factory = Proxy2.createAnonymousProxyFactory(Runnable.class, new ProxyHandler.Default() { 
      @Override
      public CallSite bootstrap(ProxyContext context) throws Throwable {
        return new ConstantCallSite(
            dropArguments(
              insertArguments(
                publicLookup().findVirtual(PrintStream.class, "println",
                    methodType(void.class, String.class)),
                0, System.out, "hello proxy"),
              0, Object.class));
      }
    });
    Runnable runnable = factory.create();
    runnable.run();
    
    factory = null;
    runnable = null;
    
    System.gc();  // should unload the proxy class
  }
}
