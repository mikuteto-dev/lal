package jp.mikumiku.lal.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jp.mikumiku.lal.agent.LALAgentBridge;
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
    private static final Set<String> TARGET_METHOD_NAMES = Set.of("getHealth", "m_21223_", "isDeadOrDying", "m_21224_", "isAlive", "m_6084_", "isRemoved", "m_240725_", "getRemovalReason", "m_146911_");
    private static final String HOOKS_CLASS = "jp/mikumiku/lal/transformer/EntityMethodHooks";

    public LALAgent() {
        super();
    }

    public static void premain(String args, Instrumentation inst) {
        LALAgent.initAgent(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        LALAgent.initAgent(inst);
    }

    private static void initAgent(Instrumentation inst) {
        LALAgentBridge.setInstrumentation(inst);
        inst.addTransformer(new LALProtectiveTransformer(), true);
    }

    public static void markProtected(String internalName) {
        PROTECTED_CLASSES.add(internalName);
    }

    private static class LALProtectiveTransformer
    implements ClassFileTransformer {
        private LALProtectiveTransformer() {
            super();
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined == null) {
                return null;
            }
            if (className == null || !PROTECTED_CLASSES.contains(className)) {
                return null;
            }
            if (className.startsWith("jp/mikumiku/lal/")) {
                return null;
            }
            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassNode classNode = new ClassNode();
                cr.accept((ClassVisitor)classNode, 0);
                if (this.hasLALHooks(classNode)) {
                    return null;
                }
                boolean modified = LALTransformer.transform(classNode);
                if (modified) {
                    ClassWriter cw = new ClassWriter(1);
                    classNode.accept((ClassVisitor)cw);
                    return cw.toByteArray();
                }
            }
            catch (Exception e) {
            }
            return null;
        }

        private boolean hasLALHooks(ClassNode classNode) {
            for (MethodNode method : classNode.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (!(insn instanceof MethodInsnNode)) continue;
                    MethodInsnNode mi = (MethodInsnNode)insn;
                    if (!LALAgent.HOOKS_CLASS.equals(mi.owner)) continue;
                    return true;
                }
            }
            return false;
        }
    }
}
