package com.github.forax.proxy2.rewriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;

import com.github.forax.proxy2.RetroRT;

public class Retro {
  static Handle RETRO_BSM = new Handle(Opcodes.H_INVOKESTATIC,
      RetroRT.class.getName().replace('.', '/'), 
      "metafactory",
      MethodType.methodType(CallSite.class, Lookup.class, String.class, MethodType.class,
                                            MethodType.class, MethodHandle.class, MethodType.class
                           ).toMethodDescriptorString());
  
  
  private static byte[] rewrite(ClassReader reader, boolean retro) {
    ClassWriter writer = new ClassWriter(reader, 0);
    ClassVisitor visitor = writer;
    visitor = new RemappingClassAdapter(visitor, new Remapper() {
      @Override
      public String map(String typeName) {   // rename to avoid name collision
        return (typeName.startsWith("org/objectweb/asm/"))? "com/github/forax/proxy2/" + typeName.substring(14): typeName;
      }
    });
    if (retro) {
      visitor = new ClassVisitor(Opcodes.ASM5, visitor) {
        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
          if (version > Opcodes.V1_7) {   // downgrade to 1.7
            version = Opcodes.V1_7;
          }
          super.visit(version, access, name, signature, superName, interfaces);
        }
        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
          MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
          return new MethodVisitor(Opcodes.ASM5, mv) {
            @Override
            public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
              if (bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) {  // this is a lambda callsite
                super.visitInvokeDynamicInsn(name, desc, RETRO_BSM, bsmArgs); 
                return;
              }
              System.out.println("bsm " + bsm);
              throw new IllegalStateException("invalid invokedynamic call");
            }
          };
        }
      };
    }
    reader.accept(visitor, ClassReader.EXPAND_FRAMES);
    return writer.toByteArray();
  }
  
  private static void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
    byte[] buffer = new byte[8192];
    int read;
    while((read = inputStream.read(buffer)) != -1) {
      outputStream.write(buffer, 0, read);
    }
  }
  
  public static void main(String[] args) throws IOException {
    Path input = Paths.get(args[0]);
    Path output = Paths.get(args[1]);
    boolean retro = args.length > 2;
    
    try(JarFile jarInput = new JarFile(input.toFile());
        JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(output))) {
      jarInput.stream()
              .forEach(entry -> {
                 try(InputStream inputStream = jarInput.getInputStream(entry)) {
                   String name = entry.getName();
                   name = (name.startsWith("org/objectweb/asm/"))? "com/github/forax/proxy2/" + name.substring(14): name;
                   outputStream.putNextEntry(new JarEntry(name));
                   
                   if (name.endsWith(".class")) {
                     ClassReader reader = new ClassReader(inputStream);
                     outputStream.write(rewrite(reader, retro));
                   } else {
                     copy(inputStream, outputStream);
                   }
                   //outputStream.closeEntry();
                 } catch (IOException e) {
                   throw new UncheckedIOException(e);
                 }
              });
    }
  }
}
