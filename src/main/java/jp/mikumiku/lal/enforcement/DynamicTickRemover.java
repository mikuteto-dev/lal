package jp.mikumiku.lal.enforcement;

import jp.mikumiku.lal.agent.LALAgentBridge;
import jp.mikumiku.lal.core.CombatRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DynamicTickRemover {

    private static volatile boolean pendingRemoval = false;
    private static final Set<String> removedCalls = ConcurrentHashMap.newKeySet();

    public static void schedulePending() {
        pendingRemoval = true;
    }

    public static void applyImmediate() {
        pendingRemoval = true;
        applyPending();
    }

    public static boolean hasPending() {
        return pendingRemoval;
    }

    public static void applyPending() {
        if (!pendingRemoval) return;
        try {
            Set<CombatRegistry.TickSource> toRemove = collectTickSourcesToRemove();
            // Kept pending on both early exits: clearing the flag up front silently dropped the request.
            if (toRemove.isEmpty()) return;
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null) return;
            pendingRemoval = false;
            TickRemovalTransformer transformer = new TickRemovalTransformer(toRemove);
            inst.addTransformer(transformer, true);
            try {
                for (String className : getTargetClassNames()) {
                    try {
                        Class<?> clazz = Class.forName(className);
                        inst.retransformClasses(clazz);
                    } catch (Throwable ignored) {}
                }
            } finally {
                inst.removeTransformer(transformer);
            }
            for (CombatRegistry.TickSource src : toRemove) {
                removedCalls.add(src.ownerClass + "." + src.methodName + src.methodDesc);
            }
        } catch (Throwable ignored) {}
    }

    private static Set<CombatRegistry.TickSource> collectTickSourcesToRemove() {
        Set<CombatRegistry.TickSource> result = new HashSet<>();
        ConcurrentHashMap<Integer, List<CombatRegistry.TickSource>> allSources = CombatRegistry.getAllTickSources();
        ConcurrentHashMap<Integer, WeakReference<Object>> killSet = CombatRegistry.getObjectKillSet();
        for (Map.Entry<Integer, List<CombatRegistry.TickSource>> entry : allSources.entrySet()) {
            if (killSet.containsKey(entry.getKey())) {
                for (CombatRegistry.TickSource src : entry.getValue()) {
                    String key = src.ownerClass + "." + src.methodName + src.methodDesc;
                    if (!removedCalls.contains(key)) {
                        result.add(src);
                    }
                }
            }
        }
        return result;
    }

    private static String[] getTargetClassNames() {
        return new String[]{
                "net.minecraft.server.MinecraftServer",
                "net.minecraft.server.level.ServerLevel"
        };
    }

    public static boolean isRemoved(String ownerClass, String methodName, String methodDesc) {
        return removedCalls.contains(ownerClass + "." + methodName + methodDesc);
    }

    private static class TickRemovalTransformer implements ClassFileTransformer {
        private final Set<CombatRegistry.TickSource> targets;

        TickRemovalTransformer(Set<CombatRegistry.TickSource> targets) {
            this.targets = targets;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined == null) return null;
            String internalName = classBeingRedefined.getName().replace('.', '/');
            if (!internalName.equals("net/minecraft/server/MinecraftServer")
                    && !internalName.equals("net/minecraft/server/level/ServerLevel")) {
                return null;
            }
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassNode classNode = new ClassNode();
                reader.accept(classNode, 0);
                boolean modified = false;
                for (MethodNode method : classNode.methods) {
                    modified |= removeTargetCalls(method);
                }
                if (!modified) return null;
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                classNode.accept(writer);
                return writer.toByteArray();
            } catch (Throwable t) {
                return null;
            }
        }

        private boolean removeTargetCalls(MethodNode method) {
            boolean modified = false;
            List<AbstractInsnNode> toRemove = new ArrayList<>();
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode mInsn = (MethodInsnNode) insn;
                    for (CombatRegistry.TickSource target : targets) {
                        if (mInsn.owner.equals(target.ownerClass)
                                && mInsn.name.equals(target.methodName)
                                && mInsn.desc.equals(target.methodDesc)) {
                            toRemove.add(insn);
                            break;
                        }
                    }
                }
            }
            for (AbstractInsnNode insn : toRemove) {
                MethodInsnNode mInsn = (MethodInsnNode) insn;
                replaceCallWithPops(method, mInsn);
                modified = true;
            }
            return modified;
        }

        private void replaceCallWithPops(MethodNode method, MethodInsnNode callInsn) {
            Type methodType = Type.getMethodType(callInsn.desc);
            Type[] argTypes = methodType.getArgumentTypes();
            Type returnType = methodType.getReturnType();
            boolean isStatic = callInsn.getOpcode() == Opcodes.INVOKESTATIC;
            List<AbstractInsnNode> pops = new ArrayList<>();
            for (int i = argTypes.length - 1; i >= 0; i--) {
                if (argTypes[i].getSize() == 2) {
                    pops.add(new InsnNode(Opcodes.POP2));
                } else {
                    pops.add(new InsnNode(Opcodes.POP));
                }
            }
            if (!isStatic) {
                pops.add(new InsnNode(Opcodes.POP));
            }
            if (returnType.getSort() != Type.VOID) {
                if (returnType.getSize() == 2) {
                    pops.add(new InsnNode(Opcodes.DCONST_0));
                } else if (returnType.getSort() == Type.FLOAT) {
                    pops.add(new InsnNode(Opcodes.FCONST_0));
                } else if (returnType.getSort() >= Type.BOOLEAN && returnType.getSort() <= Type.INT) {
                    pops.add(new InsnNode(Opcodes.ICONST_0));
                } else if (returnType.getSort() == Type.LONG) {
                    pops.add(new InsnNode(Opcodes.LCONST_0));
                } else {
                    pops.add(new InsnNode(Opcodes.ACONST_NULL));
                }
            }
            for (AbstractInsnNode pop : pops) {
                method.instructions.insertBefore(callInsn, pop);
            }
            method.instructions.remove(callInsn);
        }
    }
}
