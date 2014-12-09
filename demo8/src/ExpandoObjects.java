import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.SwitchPoint;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

/**
 * This is an example showing how to use the proxy2 API to implement expando objects.
 * An expando object is an object that accept to add supplementary fields at runtime
 * like Javascript object, Python object or Groovy and C# expando object.
 * 
 * The method {@link #createExpando(Class)} allows to create any expando by passing
 * the type of the expando, which is a subtype of {@link ExpandoObject}.
 * If the expando has no specific type, one can use {@link ExpandoObject}.
 * 
 * The implementation uses hidden class, as used by self implementation [1]
 * and javascript V8 engine [2]. The main difference is that the implementation
 * consider that the insertion of a field 'x' followed by the introduction of
 * a field 'y' should lead to the same hidden class as the introduction of
 * a field 'y' followed by the introduction of a field 'x'.
 * For that the implementation maintain a global hash map that associate
 * a set of field names to the corresponding hidden class. 
 * 
 * Each expando objects has two fields, the {@link HiddenClass hidden class} and
 * the stash which is an array of Objects containing the values of the field.
 * The hidden class contains the mapping between a field name and its index
 * into the stash. So getting the value of the field 'foo' is equivalent to
 *   {@code expando.stash[expando.hiddenClass.propertyMap.get("foo").slot]}
 * 
 * [1] http://bibliography.selflanguage.org/index.html
 * [2] https://developers.google.com/v8/design
 */
public class ExpandoObjects {
  public interface ExpandoObject {
    public void $(String propertyName, Object value);
    public Object $(String propertyName);
    
    public HiddenClass getHiddenClass();  // for debug purpose
  }
  
  public static class HiddenClass {
    private static final HashMap<Set<String>, HiddenClass> HIDDEN_CLASS_MAP = new HashMap<>();
    final HashMap<String, Property> propertyMap;
    
    private HiddenClass(HashMap<String, Property> propertyMap) {
      this.propertyMap = propertyMap;
    }
    HiddenClass() {
      this(new HashMap<>());
    }
    
    @Override
    public String toString() {
      return propertyMap
          .entrySet()
          .stream()
          .sorted(Comparator.comparingInt(entry -> entry.getValue().slot))
          .map(Map.Entry::getKey)
          .collect(Collectors.joining(",", "{", "}"));
    }
    
    MethodHandle call(Object[] stash, String propertyName) throws Throwable {
      Object value = getProperty(stash, propertyName);
      if (!(value instanceof MethodHandle)) {
        throw new NoSuchMethodError(propertyName);
      }
      return (MethodHandle)value;
    }
    
    Object getProperty(Object[] stash, String propertyName) throws Throwable {
      Property property = propertyMap.get(propertyName);
      if (property == null) {
        return null;
      }
      return stash[property.slot];
    }
    
    static void setProperty(Object proxy, HiddenClass hiddenClass, Object[] stash, MethodHandle hiddenClassSetter, MethodHandle stashSetter, String propertyName, Object value) throws Throwable {
      Property property = hiddenClass.propertyMap.get(propertyName);
      if (property != null) { // fast path
        stash[property.slot] = value;
        property.invalidate();
        return;
      }
      hiddenClass.updateProperty(proxy, stash, hiddenClassSetter, stashSetter, propertyName, value);
    }
    
    private void updateProperty(Object proxy, Object[] stash, MethodHandle hiddenClassSetter, MethodHandle stashSetter, String propertyName, Object value) throws Throwable {
      HashMap<String, Property> propertyMap = this.propertyMap;
      HashSet<String> propertyNames = new HashSet<>(propertyMap.keySet());
      propertyNames.add(propertyName);
      HiddenClass newHiddenClass = HIDDEN_CLASS_MAP.computeIfAbsent(propertyNames, __ -> {
        HashMap<String, Property> newPropertyMap = new HashMap<>(propertyMap);  // should we share properties ?
        int slot = newPropertyMap.size();
        newPropertyMap.put(propertyName, new Property(slot));
        return new HiddenClass(newPropertyMap);
      });
      
      Object[] newStash = new Object[stash.length + 1];
      HashMap<String, Property> newPropertyMap = newHiddenClass.propertyMap;
      propertyMap.forEach((name, property) -> {
        newStash[newPropertyMap.get(name).slot] = stash[property.slot]; 
      });
      newStash[newPropertyMap.get(propertyName).slot] = value; 
      stashSetter.invokeExact(proxy, newStash);
      hiddenClassSetter.invokeExact(proxy, newHiddenClass);
    }
    
    
    
    static final MethodHandle GET_PROPERTY, CALL, SET_PROPERTY;
    static {
      Lookup lookup = MethodHandles.lookup();
      try {
        GET_PROPERTY = lookup.findVirtual(HiddenClass.class, "getProperty",
            MethodType.methodType(Object.class, Object[].class, String.class));
        CALL = lookup.findVirtual(HiddenClass.class, "call",
            MethodType.methodType(MethodHandle.class, Object[].class, String.class));
        SET_PROPERTY = lookup.findStatic(HiddenClass.class, "setProperty",
            MethodType.methodType(void.class, Object.class, HiddenClass.class, Object[].class, MethodHandle.class, MethodHandle.class, String.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
  }
  
  static class Property {
    final int slot;
    SwitchPoint switchPoint;  // may be null if the property change too often
    
    private static MethodHandle[] GETTERS;
    private static MethodHandle[] SETTERS;
    static {
      GETTERS = SETTERS = new MethodHandle[0];
    }
    
    Property(int slot) {
      this.slot = slot;
      this.switchPoint = new SwitchPoint();
    }
    
    private MethodHandle accessor(MethodHandle[] array, Consumer<MethodHandle[]> updater, IntFunction<MethodHandle> creator) {
      int slot = this.slot;
      if (slot >= array.length) {
        array = Arrays.copyOf(array, slot + 1);
        updater.accept(array);
      } else {
        MethodHandle accessor = array[slot];
        if (accessor != null) {
          return accessor;
        }
      }
      return array[slot] = creator.apply(slot);
    }
    
    MethodHandle getter() {
      return accessor(GETTERS, array -> GETTERS = array, slot -> 
        MethodHandles.dropArguments(
          MethodHandles.insertArguments(
              MethodHandles.arrayElementGetter(Object[].class),
              1, slot),
          0, Object.class, HiddenClass.class));
    }
    
    MethodHandle setter() {
      MethodHandle setter =  accessor(SETTERS, array -> SETTERS = array, slot -> 
        MethodHandles.dropArguments(
          MethodHandles.insertArguments(
              MethodHandles.arrayElementSetter(Object[].class),
              1, slot),
          0, Object.class, HiddenClass.class));
      if (switchPoint == null) {
        return setter;
      }
      return MethodHandles.foldArguments(setter, INVALIDATE.bindTo(this));
    }
    
    void invalidate() {
      SwitchPoint switchPoint = this.switchPoint;
      if (switchPoint == null) {
        return;
      }
      SwitchPoint.invalidateAll(new SwitchPoint[] {switchPoint});
      this.switchPoint = new SwitchPoint();
    }
    
    private static MethodHandle INVALIDATE;
    static {
      try {
        INVALIDATE = MethodHandles.lookup().findVirtual(Property.class, "invalidate",
            MethodType.methodType(void.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
  }
  
  private static final Object[] DEFAULT_STASH = new Object[0];
  private static final HiddenClass DEFAULT_HIDDEN_CLASS = new HiddenClass();
  
  static String propertyName(String methodName) {
    String propertyName = methodName.substring(3);
    return Character.toLowerCase(propertyName.charAt(0)) + propertyName.substring(1);
  }
  
  private static final ClassValue<MethodHandle> PROXY_FACTORY = new ClassValue<MethodHandle>() {
    @Override
    protected MethodHandle computeValue(Class<?> type) {
      return Proxy2.createAnonymousProxyFactory(MethodType.methodType(type, HiddenClass.class, Object[].class),
          new ProxyHandler.Default() {
        @Override
        public boolean isMutable(int fieldIndex, Class<?> fieldType) {
          return true;
        }
        @Override
        public CallSite bootstrap(ProxyContext context) throws Throwable {
          Method method = context.method();
          String name = method.getName();
          int parameterCount = method.getParameterCount();
          if (method.getDeclaringClass() == ExpandoObject.class) {
            MethodHandle target;
            switch(name) {
            case "getHiddenClass":
              target = MethodHandles.dropArguments(
                  MethodHandles.dropArguments(
                    MethodHandles.identity(HiddenClass.class),
                    1, Object[].class),
                  0, Object.class);
              break;
            case "$":
              if (parameterCount == 1) { // get property
                target = MethodHandles.dropArguments(HiddenClass.GET_PROPERTY,
                    0, Object.class);
              } else {  // set property
                target = MethodHandles.insertArguments(HiddenClass.SET_PROPERTY, 3,
                    context.findFieldSetter(0, HiddenClass.class),
                    context.findFieldSetter(1, Object[].class));
              }
              break;
            default:
              throw new UnsupportedOperationException(method.toString());
            }
            return new ConstantCallSite(target);
          }
          
          if (name.startsWith("get") && parameterCount == 0) {
            String property = propertyName(name);
            MethodType methodType =
                MethodType.methodType(method.getReturnType(), Object.class, HiddenClass.class, Object[].class);
            return new InliningCacheCallSite(property, methodType, InliningCacheCallSite.GET_FALLBACK);
          }
          
          if (name.startsWith("set") && parameterCount == 1) {
            String property = propertyName(name);
            MethodType methodType =
                MethodType.methodType(void.class, Object.class, HiddenClass.class, Object[].class, method.getParameterTypes()[0]);
            return new InliningCacheCallSite(property, methodType,
                MethodHandles.insertArguments(InliningCacheCallSite.SET_FALLBACK,
                    4, context.findFieldSetter(0, HiddenClass.class),
                       context.findFieldSetter(1, Object[].class)));
          }
          
          MethodType methodType =
              MethodType.methodType(MethodHandle.class, Object.class, HiddenClass.class, Object[].class);
          InliningCacheCallSite callSite = new InliningCacheCallSite(method.getName(), methodType, InliningCacheCallSite.CALL_FALLBACK);
          return new ConstantCallSite(
              MethodHandles.foldArguments(
                  MethodHandles.dropArguments(
                      MethodHandles.exactInvoker(MethodType.methodType(method.getReturnType(), method.getParameterTypes())),
                      1, Object.class, HiddenClass.class, Object[].class),
                  callSite.dynamicInvoker()));
        }
      });
    }
  };
  
  static class InliningCacheCallSite extends MutableCallSite {
    private final String propertyName;
    private final MethodHandle fallback;
    private int retry;
    
    private static final int MAX_RETRY = 8;

    InliningCacheCallSite(String propertyName, MethodType methodType, MethodHandle fallbackBase) {
      super(methodType);
      this.propertyName = propertyName;
      MethodHandle fallback = fallbackBase.bindTo(this).asType(methodType);
      this.fallback = fallback;
      setTarget(fallback);
    }

    MethodHandle callFallback(Object proxy, HiddenClass hiddenClass, Object[] stash) throws Throwable {
      Property property = hiddenClass.propertyMap.get(propertyName);
      Object propertyValue;
      if (property == null || !((propertyValue = stash[property.slot]) instanceof MethodHandle)) {
        throw new NoSuchMethodError(propertyName);
      }
      MethodHandle value = (MethodHandle)propertyValue;
      SwitchPoint switchPoint = property.switchPoint;
      MethodHandle target;
      if (switchPoint != null && retry++ < MAX_RETRY) {
        MethodHandle mh = MethodHandles.dropArguments(
            MethodHandles.constant(MethodHandle.class, value),
            0, Object.class, HiddenClass.class, Object[].class);
        target = MethodHandles.guardWithTest(CHECK_HIDDEN_CLASS.bindTo(hiddenClass),
            switchPoint.guardWithTest(mh, fallback),
            fallback);
      } else { // too many different hidden classes or too many changes
        property.switchPoint = null;
        target = MethodHandles.dropArguments(
            MethodHandles.insertArguments(HiddenClass.CALL, 2, propertyName),
            0, Object.class);
      }
      setTarget(target);
      return value;
    }
    
    Object getFallback(Object proxy, HiddenClass hiddenClass, Object[] stash) throws Throwable {
      Property property = hiddenClass.propertyMap.get(propertyName);
      if (property == null) {
        return null; 
      }
      MethodHandle target;
      if (retry++ < MAX_RETRY) {
        target = MethodHandles.guardWithTest(CHECK_HIDDEN_CLASS.bindTo(hiddenClass),
            property.getter().asType(type()),
            fallback);
      } else {  // too many different hidden classes
        target = MethodHandles.dropArguments(
            MethodHandles.insertArguments(HiddenClass.GET_PROPERTY, 2, propertyName),
            0, Object.class).asType(type());
      }
      setTarget(target);
      return stash[property.slot];
    }
    
    void setFallback(Object proxy, HiddenClass hiddenClass, Object[] stash, MethodHandle hiddenClassSetter, MethodHandle stashSetter, Object value) throws Throwable {
      Property property = hiddenClass.propertyMap.get(propertyName);
      if (property == null) {
        HiddenClass.setProperty(proxy, hiddenClass, stash, hiddenClassSetter, stashSetter, propertyName, value);
        return;
      }
      
      // the property has a new value
      property.invalidate();
      
      MethodHandle target;
      if (retry++ < MAX_RETRY) {  
        target = MethodHandles.guardWithTest(CHECK_HIDDEN_CLASS.bindTo(hiddenClass),
            property.setter().asType(type()),
            fallback);
      } else {
        target = MethodHandles.insertArguments(HiddenClass.SET_PROPERTY,
            3, hiddenClassSetter, stashSetter, propertyName).asType(type());
      }
      setTarget(target);
      stash[property.slot] = value;
    }
    
    static boolean checkHiddenClass(HiddenClass exptectedHiddenClass, Object proxy, HiddenClass hiddenClass) {
      return exptectedHiddenClass == hiddenClass;
    }
    
    private static final MethodHandle CHECK_HIDDEN_CLASS;
    static final MethodHandle CALL_FALLBACK, GET_FALLBACK, SET_FALLBACK;
    static {
      Lookup lookup = MethodHandles.lookup();
      try {
        CALL_FALLBACK = lookup.findVirtual(InliningCacheCallSite.class, "callFallback",
            MethodType.methodType(MethodHandle.class, Object.class, HiddenClass.class, Object[].class));
        GET_FALLBACK = lookup.findVirtual(InliningCacheCallSite.class, "getFallback",
            MethodType.methodType(Object.class, Object.class, HiddenClass.class, Object[].class));
        SET_FALLBACK = lookup.findVirtual(InliningCacheCallSite.class, "setFallback",
            MethodType.methodType(void.class, Object.class, HiddenClass.class, Object[].class, MethodHandle.class, MethodHandle.class, Object.class));
        CHECK_HIDDEN_CLASS = lookup.findStatic(InliningCacheCallSite.class, "checkHiddenClass",
            MethodType.methodType(boolean.class, HiddenClass.class, Object.class, HiddenClass.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
  }
  
  public static <T extends ExpandoObject> T createExpando(Class<T> type) {
    MethodHandle mh = PROXY_FACTORY.get(type);
    try {
      return type.cast(mh.invoke(DEFAULT_HIDDEN_CLASS, DEFAULT_STASH));
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }
  
  
  // --- test ---
  
  public interface Point extends ExpandoObject {
    int getX();
    void setX(int x);
    int getY();
    void setY(int y);
  }
  
  public interface Hello extends ExpandoObject {
    void hello(String s);
  }
  
  static void hello(String s) {
    System.out.println("hello " + s);
  }
  static void hello2(String s) {
    System.out.println("hello2 " + s);
  }
  
  public static void main(String[] args) throws NoSuchMethodException, IllegalAccessException {
    Point point = createExpando(Point.class);
    point.$("x", 1);
    System.out.println(point.getHiddenClass());
    point.setY(1);
    point.setY(2);
    System.out.println(point.getHiddenClass());
    System.out.println(point.getX() + " " + point.getY());
    
    ExpandoObject point2 = createExpando(ExpandoObject.class);
    point2.$("y", 2);
    System.out.println(point2.getHiddenClass());
    point2.$("x", 1);
    System.out.println(point2.getHiddenClass());
    System.out.println(point.$("x") + " " + point.$("y"));
    
    
    // and with methods
    MethodHandle hello = MethodHandles.lookup().findStatic(ExpandoObjects.class, "hello",
        MethodType.methodType(void.class, String.class));
    MethodHandle hello2 = MethodHandles.lookup().findStatic(ExpandoObjects.class, "hello2",
        MethodType.methodType(void.class, String.class));
    Hello h = createExpando(Hello.class);
    Runnable runnable = () -> h.hello("expando");
    h.$("hello", hello);
    runnable.run();
    runnable.run();
    h.$("hello", hello2);
    runnable.run();
  }
}
