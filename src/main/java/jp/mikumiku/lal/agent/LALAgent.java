package jp.mikumiku.lal.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jp.mikumiku.lal.transformer.LALTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

public class LALAgent {
    private static final Set<String> PROTECTED_CLASSES = ConcurrentHashMap.newKeySet();

    public static void premain(String args, Instrumentation inst) {
        initAgent(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        initAgent(inst);
    }

    private static void initAgent(Instrumentation inst) {
        LALAgentBridge.setInstrumentation(inst);
        inst.addTransformer(new LALProtectiveTransformer(), true);
    }

    public static void markProtected(String internalName) {
        PROTECTED_CLASSES.add(internalName);
    }

    public static void retransformTargetClasses() {
        Instrumentation inst = LALAgentBridge.getInstrumentation();
        if (inst == null) return;
        try {
            Class<?>[] targets = {
                    net.minecraft.world.entity.Entity.class,
                    net.minecraft.world.entity.LivingEntity.class,
                    net.minecraft.world.entity.player.Player.class,
                    net.minecraft.server.level.ServerPlayer.class,
                    net.minecraft.server.level.ServerLevel.class
            };
            for (Class<?> c : targets) {
                try { inst.retransformClasses(c); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static class LALProtectiveTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined == null) return null;
            if (className == null || !PROTECTED_CLASSES.contains(className)) return null;
            if (className.startsWith("jp/mikumiku/lal/")) return null;
            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassNode classNode = new ClassNode();
                cr.accept(classNode, 0);

                boolean modified = LALTransformer.transform(classNode);
                if (modified) {
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                        @Override
                        protected String getCommonSuperClass(String type1, String type2) {
                            try {
                                return super.getCommonSuperClass(type1, type2);
                            } catch (Exception e) {
                                return "java/lang/Object";
                            }
                        }
                    };
                    classNode.accept(cw);
                    return cw.toByteArray();
                }
            } catch (Exception ignored) {}
            return null;
        }

    }
}
