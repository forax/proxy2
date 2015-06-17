import static com.github.forax.proxy2.MethodBuilder.methodBuilder;
import static java.lang.invoke.MethodHandles.publicLookup;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;

import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyFactory;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public interface Hello {
  public String message(String message, String user);
  
  public static void main(String[] args) {
    ProxyFactory<Hello> factory = Proxy2.createAnonymousProxyFactory(Hello.class, new ProxyHandler.Default() { 
      @Override
      public CallSite bootstrap(ProxyContext context) throws Throwable {
        System.out.println("bootstrap method " + context.method());
        System.out.println("bootstrap type " + context.type());
        MethodHandle target =
            methodBuilder(context.type())
              .dropFirst()
              .unreflect(publicLookup(), String.class.getMethod("concat", String.class));
        return new ConstantCallSite(target);
      }
    });
    
    Hello simple = factory.create();
    System.out.println(simple.message("hello ", "proxy"));
    System.out.println(simple.message("hello ", "proxy 2"));
  }
}
