package dev.solvit.buildtools;

import org.gradle.api.artifacts.transform.*;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Adapt only JGit stream calls absent on Android 26; retain upstream Git algorithms. */
@CacheableTransform
public abstract class AndroidJgitTransform implements TransformAction<TransformParameters.None> {
    @InputArtifact @Classpath
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override public void transform(TransformOutputs outputs) {
        File input = getInputArtifact().get().getAsFile();
        File output = outputs.file(input.getName());
        try (ZipFile source = new ZipFile(input)) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            boolean modified = false;
            Enumeration<? extends ZipEntry> iterator = source.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                byte[] bytes;
                try (InputStream stream = source.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                if (entry.getName().startsWith("org/eclipse/jgit/") && entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(0);
                    final boolean[] changed = {false};
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9,
                                    super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                @Override public void visitMethodInsn(int opcode, String owner, String method,
                                        String desc, boolean isInterface) {
                                    String replacement = null;
                                    if (opcode == Opcodes.INVOKEVIRTUAL) {
                                        if (method.equals("readNBytes") && desc.equals("(I)[B"))
                                            replacement = "(Ljava/io/InputStream;I)[B";
                                        else if (method.equals("readNBytes") && desc.equals("([BII)I"))
                                            replacement = "(Ljava/io/InputStream;[BII)I";
                                        else if (method.equals("readAllBytes") && desc.equals("()[B"))
                                            replacement = "(Ljava/io/InputStream;)[B";
                                    }
                                    if (replacement != null) {
                                        changed[0] = true;
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/solvit/git/AndroidInputStreams",
                                                method, replacement, false);
                                    } else super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                                }
                            };
                        }
                    }, 0);
                    if (changed[0]) { bytes = writer.toByteArray(); modified = true; }
                }
                entries.put(entry.getName(), bytes);
            }
            try (ZipOutputStream target = new ZipOutputStream(new FileOutputStream(output))) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    String name = entry.getKey(), upper = name.toUpperCase(Locale.ROOT);
                    // Bytecode changes invalidate an upstream jar signature; preserve its license and manifest.
                    if (modified && upper.startsWith("META-INF/") &&
                            (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) continue;
                    ZipEntry copy = new ZipEntry(name); copy.setTime(0);
                    target.putNextEntry(copy); target.write(entry.getValue()); target.closeEntry();
                }
            }
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
