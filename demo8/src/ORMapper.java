import static com.github.forax.proxy2.MethodBuilder.methodBuilder;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import com.github.forax.proxy2.MethodBuilder;
import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public class ORMapper {
  public static class TransactionManager {
    private static final ThreadLocal<HashSet<Object>> transactions = ThreadLocal.withInitial(HashSet::new);

    public static void markDirty(Object o) {
      transactions.get().add(o);
    }

    public static Set<Object> getDirtySetAndClear() {
      HashSet<Object> set = transactions.get();
      transactions.remove();
      return set;
    }
  }

  final ClassValue<MethodHandle> beanFactories = new ClassValue<MethodHandle>() {
    @Override
    protected MethodHandle computeValue(Class<?> type) {
      return Proxy2.createAnonymousProxyFactory(publicLookup(), methodType(type, HashMap.class), new ProxyHandler.Default() {
        @Override
        public CallSite bootstrap(ProxyContext context) throws Throwable {
          MethodHandle target;
          Method method = context.method();
          MethodBuilder builder = methodBuilder(context.type());
          switch(method.getName()) {
          case "toString":
            target = builder
                .dropFirst()
                .convertTo(String.class, AbstractMap.class)  //FIXME
                .unreflect(publicLookup(), HashMap.class.getMethod("toString"));
            break;
          default:
            if (method.getParameterCount() == 0) { 
              target = builder                     // getter
                  .dropFirst()
                  .insertAt(1, Object.class, method.getName())
                  .convertTo(Object.class, HashMap.class, Object.class)
                  .unreflect(publicLookup(), HashMap.class.getMethod("get", Object.class));
            } else {                               
              target = builder                     // setter
                  .before(b -> b
                      .dropFirst()
                      .insertAt(1, Object.class, method.getName())
                      .convertTo(Object.class, HashMap.class, Object.class, Object.class)
                      .unreflect(publicLookup(), HashMap.class.getMethod("put", Object.class, Object.class)))
                  .dropAt(1)
                  .dropAt(1)
                  .before(b -> b
                      .unreflect(publicLookup(), TransactionManager.class.getMethod("markDirty", Object.class)))
                  .convertTo(method.getReturnType(), Object.class)
                  .callIdentity();
            }
          }
          return new ConstantCallSite(target);
        }
      });
    }
  };

  static <T> T newBean(ClassValue<MethodHandle> factories, Class<T> type) {
    return newInstance(factories, type, new HashMap<>());
  }

  private static <T> T newInstance(ClassValue<MethodHandle> factories, Class<T> type, Object... args) {
    try {
      return type.cast(factories.get(type).invokeWithArguments(args));
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  private final ClassValue<MethodHandle> serviceFactories = new ClassValue<MethodHandle>() {
    @Override
    protected MethodHandle computeValue(Class<?> type) {
      Lookup lookup = lookup();
      return Proxy2.createAnonymousProxyFactory(publicLookup(), methodType(type), new ProxyHandler.Default() {
        @Override
        public CallSite bootstrap(ProxyContext context) throws Throwable {
          Method method = context.method();
          switch(method.getName()) {
          case "create":
            MethodHandle target = methodBuilder(context.type())
                .dropFirst()
                .insertAt(0, ClassValue.class, beanFactories)
                .insertAt(1, Class.class, method.getReturnType())
                .convertTo(Object.class, ClassValue.class, Class.class)
                .unreflect(lookup, ORMapper.class.getDeclaredMethod("newBean", ClassValue.class, Class.class));
            return new ConstantCallSite(target);
          default:
            throw new NoSuchMethodError(method.toString());
          }
        }
      });
    }
  };

  public <T> T createService(Class<T> type) {
    return newInstance(serviceFactories, type);
  }

  
  // --- example
  
  public interface SQLUser {
    public int id();
    public SQLUser id(int id);
    public String name();
    public SQLUser name(String name);

    @Override
    public String toString();  //FIXME remove when Object methods will be supported
  }

  public interface SQLService {
    public SQLUser create();
  }

  public static void main(String[] args) {
    ORMapper mapper = new ORMapper();
    SQLService sqlService = mapper.createService(SQLService.class);
    SQLUser user = sqlService.create().id(3).name("Bob");
    System.out.println(user.name());
    System.out.println(TransactionManager.getDirtySetAndClear());
  }
}
