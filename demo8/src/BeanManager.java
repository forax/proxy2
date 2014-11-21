import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.AbstractMap;
import java.util.HashMap;

import com.github.forax.proxy2.MethodBuilder;
import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public class BeanManager {
  final ClassValue<MethodHandle> beanFactories = new ClassValue<MethodHandle>() {
    @Override
    protected MethodHandle computeValue(Class<?> type) {
      return Proxy2.createAnonymousProxyFactory(MethodHandles.publicLookup(), MethodType.methodType(type, HashMap.class), new ProxyHandler() {
        @Override
        public boolean override(Method method) {
          return true;
        }

        @Override
        public CallSite bootstrap(Lookup lookup, Method method) throws Throwable {
          MethodHandle target;
          MethodBuilder builder = MethodBuilder.methodBuilder(method, HashMap.class);
          switch(method.getName()) {
          case "toString":
            target = builder
                .dropFirstParameter()
                .convertTo(String.class, AbstractMap.class)  // FIXME
                .thenCall(lookup, HashMap.class.getMethod("toString"));
            break;
          default:
            if (method.getParameterCount() == 0) { 
              target = builder                     // getter
                  .dropFirstParameter()
                  .insertValueAt(0, Object.class, method.getName())
                  .convertTo(Object.class, Object.class)
                  .thenCall(lookup, HashMap.class.getMethod("get", Object.class));
            } else {                               
              target = builder                     // setter
                  .before(b -> b
                      .dropFirstParameter()
                      .insertValueAt(1, Object.class, method.getName())
                      .convertTo(Object.class, HashMap.class, Object.class, Object.class)
                      .thenCall(lookup, HashMap.class.getMethod("put", Object.class, Object.class)))
                  .dropParameterAt(1)
                  .dropParameterAt(1)
                  .convertTo(method.getReturnType(), Object.class)
                  .thenCallIdentity();
            }
          }
          return new ConstantCallSite(target);
        }
      });
    }
  };

  public <T> T newBean(Class<T> type) {
    try {
      return type.cast(beanFactories.get(type).invoke(new HashMap<String,Object>()));
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  
  
  // --- example
  
  public interface User {
    public String firstName();
    public User firstName(String name);
    public String lastName();
    public User lastName(String name);
    public int age();
    public User age(int age);

    @Override
    public String toString();
  }

  public static void main(String[] args) {
    BeanManager beanManager = new BeanManager();
    User user = beanManager.newBean(User.class);
    user.firstName("Fox").lastName("Mulder").age(30);
    System.out.println(user);
  }
}
