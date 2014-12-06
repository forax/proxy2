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
      return Proxy2.createAnonymousProxyFactory(MethodType.methodType(type, HashMap.class), new ProxyHandler.Default() {
        @Override
        public CallSite bootstrap(ProxyContext context) throws Throwable {
          MethodHandle target;
          Lookup lookup = MethodHandles.publicLookup();
          Method method = context.getProxyMethod();
          MethodBuilder builder = MethodBuilder.methodBuilder(method, HashMap.class);
          switch(method.getName()) {
          case "toString":
            target = builder
                .dropFirstParameter()
                .convertTo(String.class, AbstractMap.class)  //FIXME
                .thenCall(lookup, HashMap.class.getMethod("toString"));
            break;
          default:
            if (method.getParameterCount() == 0) { 
              target = builder                     // getter
                  .dropFirstParameter()
                  .insertValueAt(1, Object.class, method.getName())
                  .convertTo(Object.class, HashMap.class, Object.class)
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
                  .before(b -> b
                      .thenCall(lookup, TransactionManager.class.getMethod("markDirty", Object.class)))
                  .convertTo(method.getReturnType(), Object.class)
                  .thenCallIdentity();
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
      Lookup lookup = MethodHandles.lookup();
      return Proxy2.createAnonymousProxyFactory(MethodType.methodType(type), new ProxyHandler.Default() {
        @Override
        public CallSite bootstrap(ProxyContext context) throws Throwable {
          Method method = context.getProxyMethod();
          switch(method.getName()) {
          case "create":
            MethodHandle target = MethodBuilder.methodBuilder(method)
                .dropFirstParameter()
                .insertValueAt(0, ClassValue.class, beanFactories)
                .insertValueAt(1, Class.class, method.getReturnType())
                .convertTo(Object.class, ClassValue.class, Class.class)
                .thenCall(lookup, ORMapper.class.getDeclaredMethod("newBean", ClassValue.class, Class.class));
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
