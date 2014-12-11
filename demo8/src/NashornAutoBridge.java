import static com.github.forax.proxy2.MethodBuilder.methodBuilder;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;

import java.io.IOException;
import java.io.Reader;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.IntConsumer;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import jdk.nashorn.api.scripting.ScriptObjectMirror;

import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public class NashornAutoBridge {
  private static final ClassValue<MethodHandle> CACHE = new ClassValue<MethodHandle>() {
    @Override
    protected MethodHandle computeValue(Class<?> type) {
      MethodHandle mh = Proxy2.createAnonymousProxyFactory(publicLookup(), methodType(type, ScriptObjectMirror.class), new ProxyHandler.Default() {
        @Override
        public CallSite bootstrap(ProxyContext context) throws Throwable {
          Method method = context.method();
          String name = method.getName();
          MethodHandle target;
          if (name.equals("__getScriptObjectMirror__")) {
            target = methodBuilder(context.type())
                .dropFirstParameter()
                .thenCallIdentity();
          } else {
            if (name.startsWith("get")) {
              String property = propertyName(name);
              target = methodBuilder(context.type())
                  .dropFirstParameter()
                  .convertReturnTypeTo(Object.class)
                  .insertValueAt(1, String.class, property)
                  .compose(Object.class, b -> b.thenCallMethodHandle(GET_MEMBER))
                  .thenCallMethodHandle(WRAP.bindTo(method.getReturnType()));
            } else {
              if (name.startsWith("set")) {
                String property = propertyName(name);
                target = methodBuilder(context.type())
                    .dropFirstParameter()
                    .convertTo(void.class, ScriptObjectMirror.class, Object.class)
                    .insertValueAt(1, String.class, property)
                    .filter(2, Object.class, b -> b.thenCallMethodHandle(UNWRAP))
                    .thenCallMethodHandle(SET_MEMBER);
              } else {
                int argumentCount = method.getParameterCount();
                target = methodBuilder(context.type())
                    .dropFirstParameter()
                    .convertReturnTypeTo(Object.class)
                    .insertValueAt(1, String.class, name)
                    .filterLastArguments(argumentCount, Object.class, Object.class, b -> b.thenCallMethodHandle(UNWRAP))
                    .boxLastArguments(argumentCount)
                    .compose(Object.class, b -> b.thenCallMethodHandle(CALL_MEMBER))
                    .thenCallMethodHandle(WRAP.bindTo(method.getReturnType()));
              }
            }
          }
          return new ConstantCallSite(target);
        }
      });
      return mh.asType(methodType(Object.class, ScriptObjectMirror.class));
    }
  };
  
  static String propertyName(String name) {
    String property = name.substring(3);
    return Character.toLowerCase(property.charAt(0)) + property.substring(1);
  }
  
  static Object unwrap(Object o) {
    if (o instanceof Bridge) {
      return ((Bridge)o).__getScriptObjectMirror__();
    }
    return o;
  }
  
  static Object wrap(Class<?> returnType, Object o) {
    if (o instanceof ScriptObjectMirror && Bridge.class.isAssignableFrom(returnType)) {
      return createBridge(returnType, (ScriptObjectMirror)o);
    }
    return o;
  }
  
  private static Object createBridge(Class<?> type, ScriptObjectMirror mirror) {
    try {
      return CACHE.get(type).invokeExact(mirror);
    } catch (Error | RuntimeException e) {
      throw e;
    } catch(Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }
  
  static final MethodHandle WRAP, UNWRAP,
                            GET_MEMBER, SET_MEMBER, CALL_MEMBER;
  static {
    Lookup lookup = lookup();
    try {
      WRAP = lookup.findStatic(NashornAutoBridge.class, "wrap",
          methodType(Object.class, Class.class, Object.class));
      UNWRAP = lookup.findStatic(NashornAutoBridge.class, "unwrap",
          methodType(Object.class, Object.class));
      GET_MEMBER = publicLookup().findVirtual(ScriptObjectMirror.class, "getMember",
          methodType(Object.class, String.class));
      SET_MEMBER = publicLookup().findVirtual(ScriptObjectMirror.class, "setMember",
          methodType(void.class, String.class, Object.class));
      CALL_MEMBER = publicLookup().findVirtual(ScriptObjectMirror.class, "callMember",
          methodType(Object.class, String.class, Object[].class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
  
  public interface Bridge {
    public ScriptObjectMirror __getScriptObjectMirror__();
  }
  
  public static <T extends Bridge> T bridge(Class<T> type, ScriptObjectMirror mirror) {
    return type.cast(createBridge(type, mirror));
  }
  
  
  // --- test ---
  
  public interface FunList extends Bridge {
    int size();
    public void forEach(IntConsumer consumer);
  }
  public interface FunCons extends FunList {
    int getValue();
    void setValue(int value);
    FunList getNext();
  }
  public interface FunListFactory extends Bridge {
    public FunCons cons(int value, FunList next);
    public FunList nil();
  }
  
  public static void main(String[] args) throws ScriptException, IOException {
    ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
    try(Reader reader = Files.newBufferedReader(Paths.get("demo8/funlist.js"))) {
      engine.eval(reader);
    }
    ScriptObjectMirror global = (ScriptObjectMirror)engine.eval("this");
    
    FunListFactory f = bridge(FunListFactory.class, global);
    FunCons list = f.cons(666, f.cons(2, f.cons(3, f.nil())));
    // System.out.println(list.getValue());
    // System.out.println(list.getNext());
  
    //System.out.println(list.__getScriptObjectMirror__().entrySet());
    
    list.setValue(1);
    
    System.out.println(list.size());
    list.forEach(System.out::println);
  }
}
