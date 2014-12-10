Proxy 2.0
=========

Better / faster proxy generator than java.lang.reflect.Proxy for Java (require 1.7)


Getting Started
===============

The Proxy2 API is composed of only 2 main classes. The class *Proxy2* that defines several static methods
named *createAnonymousProxyFactory* which as their names indicate return a factory of proxy instances (objects).
To create a proxy, you need to pass an interface that will be the interface implemented by the proxy
and a *ProxyHandler* that will define the implementation of all the methods od the proxy class.

The method bootstrap of the *ProxyHandler* is somewhat lazy and will be called *once* by method the first time
the implementation of a method is needed.  Because the method is called once, the bootstrap method has no access
to the arguments of the method but have access the method of the interface that should be implemented
(as a reflection *Method* object) and the signature of the proxy implementation (as a java.lang.invoke.MethodType object).
These two values are encapsulated in the *ProxyContext* object.
The result of the bootstrap method is a callsite (a java.lang.invoke.CallSite objet) that allows to change
the implementation of the method at runtime (by calling setTarget()) if needed.

The second class of the API, *MethodBuilder* is a builder that helps to describe the implementation
of a proxy method. While this class is not strictly necessary, the *MethodHandles* API is not
an easy API to master and I believe that the class *MethodBuilder* provide a good entry level API.   

Let's take a simple example, 

```java
public interface Hello {
  public String message(String message, String user);
  
  public static void main(String[] args) {
    ProxyFactory<Hello> factory = Proxy2.createAnonymousProxyFactory(Hello.class, new ProxyHandler.Default() { 
      @Override
      public CallSite bootstrap(ProxyContext context) throws Throwable {
        System.out.println("bootstrap method " + context.method());
        System.out.println("bootstrap type " + context.type());
        MethodHandle target =
            Methodbuilder.methodBuilder(context.type())
              .dropFirstParameter()
              .thenCall(MethodHandles.publicLookup(), String.class.getMethod("concat", String.class));
        return new ConstantCallSite(target);
      }
    });
    
    Hello simple = factory.create();
    System.out.println(simple.message("hello ", "proxy"));
    System.out.println(simple.message("hello ", "proxy 2"));
  }
}
```

The code above create a proxy factory on the interface *Hello* which has a single method *message*.
The first time the method *message* is called, it called the method *bootstrap* of the *ProxyHandler*,
which use a *MethodBuilder* to create to the implementation.

Here is the output of the code above
```
bootstrap method public abstract java.lang.String Hello.message(java.lang.String,java.lang.String)
bootstrap type (Object,String,String)String
hello proxy
hello proxy 2
```

In order to implement the method *message*, the method *bootstrap* has to provide a *MethodMethod*
(a glorified function pointer) that takes an object and two strings and return a string.
The first object, correspond to the proxy object, the two strings to the two arguments of *message*
and string returned correspond to the return type of *message*.

Here the implementation decide to call *String.concat*, for that the proxy object is not needed,
that's why *dropFirstParameter()* is called on the builder before asking to call *String.concat*.

As you can see, the method *bootstrap* is called once even if the method *message* is called twice,
because the method *bootstrap* acts as a linker between the abstract method defined
in the interface and its implementation. 

