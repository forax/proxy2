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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.function.IntConsumer;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import jdk.nashorn.api.scripting.ScriptObjectMirror;

import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public class NashornAutoBridgeProxy1 {
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
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
        (Object proxy, Method method, Object[] args) -> {
            String name = method.getName();
            if (name.equals("__getScriptObjectMirror__")) {
              return mirror;
            }
            if (name.startsWith("get")) {
              String property = propertyName(name);
              return wrap(method.getReturnType(), mirror.getMember(property));
            }
            if (name.startsWith("set")) {
              String property = propertyName(name);
              mirror.setMember(property, unwrap(args[0]));
              return null;
            }
            if (args != null) {
              Arrays.setAll(args, i -> unwrap(args[i]));
            }
            return wrap(method.getReturnType(), mirror.callMember(name, args));
        }));
  }
  
  static final MethodHandle WRAP, UNWRAP,
                            GET_MEMBER, SET_MEMBER, CALL_MEMBER;
  static {
    Lookup lookup = lookup();
    try {
      WRAP = lookup.findStatic(NashornAutoBridgeProxy1.class, "wrap",
          methodType(Object.class, Class.class, Object.class));
      UNWRAP = lookup.findStatic(NashornAutoBridgeProxy1.class, "unwrap",
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
  public interface FunListFactory extends Bridge {
    public FunList cons(int value, FunList next);
    public FunList nil();
  }
  
  public static void main(String[] args) throws ScriptException, IOException {
    ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
    try(Reader reader = Files.newBufferedReader(Paths.get("demo8/funlist.js"))) {
      engine.eval(reader);
    }
    ScriptObjectMirror global = (ScriptObjectMirror)engine.eval("this");
    
    FunListFactory f = bridge(FunListFactory.class, global);
    FunList list = f.cons(1, f.cons(2, f.cons(3, f.nil())));
    
    System.out.println(list.size());
    list.forEach(System.out::println);
  }
}
