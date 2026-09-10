package jp.mikumiku.lal.enforcement;

import jp.mikumiku.lal.core.CombatRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TickSourceLocator {

    private static final Set<String> ALLOWED_OWNERS = Set.of(
            "net/minecraft/", "net/minecraftforge/", "com/mojang/",
            "jp/mikumiku/lal/", "java/", "javax/", "sun/", "jdk/",
            "org/spongepowered/", "cpw/mods/", "it/unimi/"
    );

    private static final String[] TICK_METHOD_NAMES = {
            "m_130011_", "tickServer",
            "m_8793_", "tick",
            "m_5703_", "tickPassenger"
    };

    private static final String[] TICK_CLASSES = {
            "net.minecraft.server.MinecraftServer",
            "net.minecraft.server.level.ServerLevel"
    };

    private static volatile List<CombatRegistry.TickSource> cachedExternalCalls;
    private static volatile long lastScanTime;
    private static final long SCAN_COOLDOWN_MS = 5000;

    public static void locate(Object targetObj) {
        if (targetObj == null) return;
        String targetClassName = targetObj.getClass().getName().replace('.', '/');
        String packagePrefix = targetClassName.substring(0, targetClassName.lastIndexOf('/') + 1);
        try {
            List<CombatRegistry.TickSource> externalCalls = getExternalCalls();
            for (CombatRegistry.TickSource source : externalCalls) {
                // startsWith("") matches everything, so a default-package class needs an exact match.
                boolean matches = source.ownerClass.equals(targetClassName)
                        || (!packagePrefix.isEmpty() && source.ownerClass.startsWith(packagePrefix));
                if (matches) {
                    CombatRegistry.registerTickSource(targetObj, source);
                    DynamicTickRemover.schedulePending();
                }
            }
        } catch (Throwable ignored) {}
    }

    public static List<CombatRegistry.TickSource> getExternalCalls() {
        long now = System.currentTimeMillis();
        List<CombatRegistry.TickSource> cached = cachedExternalCalls;
        if (cached != null && (now - lastScanTime) < SCAN_COOLDOWN_MS) {
            return cached;
        }
        List<CombatRegistry.TickSource> results = new ArrayList<>();
        try {
            for (String className : TICK_CLASSES) {
                try {
                    Class<?> clazz = Class.forName(className);
                    byte[] bytes = getClassBytes(clazz);
                    if (bytes == null) continue;
                    results.addAll(scanBytecodeForExternalCalls(bytes));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        cachedExternalCalls = results;
        lastScanTime = now;
        return results;
    }

    /**
     * Read from the classloader: retransforming to capture the bytes redefined MinecraftServer and
     * ServerLevel, the two hottest server classes, every time the cache expired.
     */
    private static byte[] getClassBytes(Class<?> targetClass) {
        String resource = targetClass.getName().replace('.', '/') + ".class";
        try (java.io.InputStream is = targetClass.getResourceAsStream("/" + resource)) {
            if (is != null) return is.readAllBytes();
        } catch (Throwable ignored) {}
        try {
            ClassLoader cl = targetClass.getClassLoader();
            if (cl != null) {
                try (java.io.InputStream is = cl.getResourceAsStream(resource)) {
                    if (is != null) return is.readAllBytes();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static List<CombatRegistry.TickSource> scanBytecodeForExternalCalls(byte[] classBytes) {
        List<CombatRegistry.TickSource> results = new ArrayList<>();
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, ClassReader.SKIP_FRAMES);
            Set<String> tickMethodSet = new HashSet<>();
            for (String name : TICK_METHOD_NAMES) tickMethodSet.add(name);
            for (MethodNode method : classNode.methods) {
                if (!tickMethodSet.contains(method.name)) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode mInsn = (MethodInsnNode) insn;
                        if (!isAllowedOwner(mInsn.owner)) {
                            results.add(new CombatRegistry.TickSource(mInsn.owner, mInsn.name, mInsn.desc));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return results;
    }

    private static boolean isAllowedOwner(String owner) {
        for (String allowed : ALLOWED_OWNERS) {
            if (owner.startsWith(allowed)) return true;
        }
        return false;
    }

    public static void invalidateCache() {
        cachedExternalCalls = null;
        lastScanTime = 0;
    }
}
