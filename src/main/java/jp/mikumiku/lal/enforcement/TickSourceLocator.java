package jp.mikumiku.lal.enforcement;

import jp.mikumiku.lal.agent.LALAgentBridge;
import jp.mikumiku.lal.core.CombatRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.Instrumentation;
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
        try {
            List<CombatRegistry.TickSource> externalCalls = getExternalCalls();
            for (CombatRegistry.TickSource source : externalCalls) {
                if (source.ownerClass.equals(targetClassName) ||
                        source.ownerClass.startsWith(targetClassName.substring(0, Math.min(targetClassName.lastIndexOf('/') + 1, targetClassName.length())))) {
                    CombatRegistry.registerTickSource(targetObj, source);
                    DynamicTickRemover.schedulePending();
                }
            }
        } catch (Throwable ignored) {}
    }

    public static List<CombatRegistry.TickSource> getExternalCalls() {
        long now = System.currentTimeMillis();
        if (cachedExternalCalls != null && (now - lastScanTime) < SCAN_COOLDOWN_MS) {
            return cachedExternalCalls;
        }
        List<CombatRegistry.TickSource> results = new ArrayList<>();
        try {
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null) return results;
            for (String className : TICK_CLASSES) {
                try {
                    Class<?> clazz = Class.forName(className);
                    byte[] bytes = getClassBytes(inst, clazz);
                    if (bytes == null) continue;
                    results.addAll(scanBytecodeForExternalCalls(bytes));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        cachedExternalCalls = results;
        lastScanTime = now;
        return results;
    }

    private static byte[] getClassBytes(Instrumentation inst, Class<?> targetClass) {
        try {
            final byte[][] holder = new byte[1][];
            java.lang.instrument.ClassFileTransformer extractor = new java.lang.instrument.ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String name, Class<?> cls,
                                        java.security.ProtectionDomain domain, byte[] buf) {
                    if (cls == targetClass) holder[0] = buf.clone();
                    return null;
                }
            };
            inst.addTransformer(extractor, true);
            try {
                inst.retransformClasses(targetClass);
            } finally {
                inst.removeTransformer(extractor);
            }
            return holder[0];
        } catch (Throwable t) {
            return null;
        }
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
