package jp.mikumiku.lal.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jp.mikumiku.lal.transformer.LALTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class LALAgent {
    private static final Set<String> PROTECTED_CLASSES = ConcurrentHashMap.newKeySet();
    private static final Set<String> TARGET_METHOD_NAMES = Set.of(
            "getHealth", "m_21223_",
            "isDeadOrDying", "m_21224_",
            "isAlive", "m_6084_",
            "isRemoved", "m_240725_", "m_213877_",
            "getRemovalReason", "m_146911_",
            "canBeCollidedWith", "m_6087_",
            "isPickable", "m_6863_",
            "getBoundingBox", "m_20191_",
            "hurt", "m_6469_",
            "die", "m_6667_",
            "setHealth", "m_21154_",
            "tickDeath", "m_21230_",
            "actuallyHurt", "m_6550_",
            "knockback", "m_6660_",
            "removeAllEffects", "m_21165_",
            "shouldDropLoot", "m_7832_",
            "shouldDropExperience", "m_6085_",
            "baseTick", "m_6075_",
            "tick", "m_8119_",
            "setPose", "m_20124_",
            "setRemoved", "m_146870_",
            "kill", "m_6074_",
            "discard", "m_142687_",
            "remove", "m_142659_",
            "move", "m_6091_",
            "setPosRaw", "m_20344_",
            "setDeltaMovement", "m_20257_",
            "push", "m_5765_",
            "setNoGravity", "m_20259_",
            "getX", "m_20185_",
            "getY", "m_20186_",
            "getZ", "m_20189_",
            "position", "m_20182_",
            "attack", "m_7324_",
            "addFreshEntity", "m_7967_"
    );
    private static final String HOOKS_CLASS = "jp/mikumiku/lal/transformer/EntityMethodHooks";

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
                if (hasLALHooks(classNode)) return null;

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

        private boolean hasLALHooks(ClassNode classNode) {
            for (MethodNode method : classNode.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (!(insn instanceof MethodInsnNode)) continue;
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if (HOOKS_CLASS.equals(mi.owner)) return true;
                }
            }
            return false;
        }
    }
}
