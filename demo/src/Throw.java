import static com.github.forax.proxy2.MethodBuilder.methodBuilder;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyFactory;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public class Throw {
  public static void main(String[] args) throws Throwable {
    ProxyFactory<Runnable> factory = Proxy2.createAnonymousProxyFactory(
      Runnable.class,                        // the proxy type
      new Class<?>[] { Runnable.class },     // type of each field inside the proxy
      new ProxyHandler.Default() {
        @Override
        public boolean override(Method method) {
          return Modifier.isAbstract(method.getModifiers());
        }

        @Override
        public CallSite bootstrap(ProxyContext context) throws Throwable {
          System.out.println("bootstrap");
          Method method = context.getProxyMethod();
          MethodHandle target = methodBuilder(method, Runnable.class)    // configure the builder
              .dropFirstParameter()                                      // remove the proxy object
              .thenCall(MethodHandles.publicLookup(), method);           // call the method
          return new ConstantCallSite(target);
        }
      });
    
    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        throw null;
      }
    };
    
    Runnable proxy = factory.create(runnable);
    //Runnable proxy = factory.create((Runnable)null);
    proxy.run();
  }
}
