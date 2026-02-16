package jp.mikumiku.lal.transformer;

import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import jp.mikumiku.lal.agent.LALAgent;
import jp.mikumiku.lal.transformer.LALPlugin;
import org.objectweb.asm.Label;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
public class LALTransformer {
    private static final String HOOKS = "jp/mikumiku/lal/transformer/EntityMethodHooks";
    private static boolean initialized = false;
    private static final AtomicInteger transformedClasses = new AtomicInteger(0);
    private static final AtomicInteger transformedMethods = new AtomicInteger(0);
    private static final AtomicInteger skippedClasses = new AtomicInteger(0);
    private static final Set<String> TARGET_SIGS = Set.of("getHealth()F", "m_21223_()F", "isDeadOrDying()Z", "m_21224_()Z", "isAlive()Z", "m_6084_()Z", "isRemoved()Z", "m_240725_()Z", "m_213877_()Z", "getRemovalReason()Lnet/minecraft/world/entity/Entity$RemovalReason;", "m_146911_()Lnet/minecraft/world/entity/Entity$RemovalReason;");

    public LALTransformer() {
        super();
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        try {
            LALPlugin plugin = new LALPlugin();
            Field field = Launcher.class.getDeclaredField("launchPlugins");
            field.setAccessible(true);
            LaunchPluginHandler pluginHandler = (LaunchPluginHandler)field.get(Launcher.INSTANCE);
            field = LaunchPluginHandler.class.getDeclaredField("plugins");
            field.setAccessible(true);
            Map map = (Map)field.get(pluginHandler);
            map.put(plugin.name(), plugin);
        }
        catch (Exception e) {
        }
        initialized = true;
    }

    public static boolean transform(ClassNode classNode) {
        return LALTransformer.transform(classNode, null);
    }

    public static boolean transform(ClassNode classNode, ILaunchPluginService.Phase phase) {
        if (classNode.name.startsWith("jp/mikumiku/lal/transformer")) {
            return false;
        }
        if (!LALTransformer.hasTargetMethodReference(classNode)) {
            skippedClasses.incrementAndGet();
            return false;
        }
        boolean doHead = phase == null || phase == ILaunchPluginService.Phase.BEFORE;
        boolean doReturn = phase == null || phase == ILaunchPluginService.Phase.AFTER;
        boolean modified = false;
        for (MethodNode method : classNode.methods) {
            boolean methodModified = false;
            if (doReturn) {
                for (AbstractInsnNode insn : method.instructions) {
                    InsnList il;
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode mi = (MethodInsnNode)insn;
                        int opcode = insn.getOpcode();
                        if (opcode == 182 || opcode == 185) {
                            if (!TARGET_SIGS.contains(mi.name + mi.desc)) continue;
                            if (LALTransformer.isGetHealth(mi)) {
                                method.instructions.insertBefore((AbstractInsnNode)mi, (AbstractInsnNode)new InsnNode(89));
                                method.instructions.insert((AbstractInsnNode)mi, (AbstractInsnNode)new MethodInsnNode(184, HOOKS, "getHealth", "(Ljava/lang/Object;F)F"));
                                methodModified = true;
                            } else if (LALTransformer.isIsDeadOrDying(mi)) {
                                method.instructions.insertBefore((AbstractInsnNode)mi, (AbstractInsnNode)new InsnNode(89));
                                method.instructions.insert((AbstractInsnNode)mi, (AbstractInsnNode)new MethodInsnNode(184, HOOKS, "isDeadOrDying", "(Ljava/lang/Object;Z)Z"));
                                methodModified = true;
                            } else if (LALTransformer.isIsAlive(mi)) {
                                method.instructions.insertBefore((AbstractInsnNode)mi, (AbstractInsnNode)new InsnNode(89));
                                method.instructions.insert((AbstractInsnNode)mi, (AbstractInsnNode)new MethodInsnNode(184, HOOKS, "isAlive", "(Ljava/lang/Object;Z)Z"));
                                methodModified = true;
                            } else if (LALTransformer.isIsRemoved(mi)) {
                                method.instructions.insertBefore((AbstractInsnNode)mi, (AbstractInsnNode)new InsnNode(89));
                                method.instructions.insert((AbstractInsnNode)mi, (AbstractInsnNode)new MethodInsnNode(184, HOOKS, "isRemoved", "(Ljava/lang/Object;Z)Z"));
                                methodModified = true;
                            } else if (LALTransformer.isGetRemovalReason(mi)) {
                                method.instructions.insertBefore((AbstractInsnNode)mi, (AbstractInsnNode)new InsnNode(89));
                                method.instructions.insert((AbstractInsnNode)mi, (AbstractInsnNode)new MethodInsnNode(184, HOOKS, "getRemovalReason", "(Ljava/lang/Object;Lnet/minecraft/world/entity/Entity$RemovalReason;)Lnet/minecraft/world/entity/Entity$RemovalReason;"));
                                methodModified = true;
                            }
                        }
                    }
                    if (insn.getOpcode() == 174) {
                        if (!LALTransformer.isMethodDef(method, "m_21223_", "getHealth", "()F")) continue;
                        il = new InsnList();
                        il.add((AbstractInsnNode)new VarInsnNode(25, 0));
                        il.add((AbstractInsnNode)new MethodInsnNode(184, HOOKS, "getHealth", "(FLjava/lang/Object;)F"));
                        method.instructions.insertBefore(insn, il);
                        methodModified = true;
                        continue;
                    }
                    if (insn.getOpcode() == 172) {
                        if (LALTransformer.isMethodDef(method, "m_21224_", "isDeadOrDying", "()Z")) {
                            il = new InsnList();
                            il.add((AbstractInsnNode)new VarInsnNode(25, 0));
                            il.add((AbstractInsnNode)new MethodInsnNode(184, HOOKS, "isDeadOrDying", "(ZLjava/lang/Object;)Z"));
                            method.instructions.insertBefore(insn, il);
                            methodModified = true;
                            continue;
                        }
                        if (LALTransformer.isMethodDef(method, "m_6084_", "isAlive", "()Z")) {
                            il = new InsnList();
                            il.add((AbstractInsnNode)new VarInsnNode(25, 0));
                            il.add((AbstractInsnNode)new MethodInsnNode(184, HOOKS, "isAlive", "(ZLjava/lang/Object;)Z"));
                            method.instructions.insertBefore(insn, il);
                            methodModified = true;
                            continue;
                        }
                        if (!LALTransformer.isMethodDef(method, "m_240725_", "isRemoved", "()Z") && !LALTransformer.isMethodDef(method, "m_213877_", "isRemoved", "()Z")) continue;
                        il = new InsnList();
                        il.add((AbstractInsnNode)new VarInsnNode(25, 0));
                        il.add((AbstractInsnNode)new MethodInsnNode(184, HOOKS, "isRemoved", "(ZLjava/lang/Object;)Z"));
                        method.instructions.insertBefore(insn, il);
                        methodModified = true;
                        continue;
                    }
                    if (insn.getOpcode() != 176 || !LALTransformer.isMethodDef(method, "m_146911_", "getRemovalReason", "()Lnet/minecraft/world/entity/Entity$RemovalReason;")) continue;
                    il = new InsnList();
                    il.add((AbstractInsnNode)new VarInsnNode(25, 0));
                    il.add((AbstractInsnNode)new MethodInsnNode(184, HOOKS, "getRemovalReason", "(Lnet/minecraft/world/entity/Entity$RemovalReason;Ljava/lang/Object;)Lnet/minecraft/world/entity/Entity$RemovalReason;"));
                    method.instructions.insertBefore(insn, il);
                    methodModified = true;
                }
            }
            if (doHead) {
                if (LALTransformer.isMethodDef(method, "m_21223_", "getHealth", "()F")) {
                    LALTransformer.injectHead(method, new MethodInsnNode(184, HOOKS, "shouldReplaceMethod", "(Ljava/lang/Object;)Z", false), new MethodInsnNode(184, HOOKS, "replaceGetHealth", "(Ljava/lang/Object;)F", false), new InsnNode(174));
                    methodModified = true;
                } else if (LALTransformer.isMethodDef(method, "m_21224_", "isDeadOrDying", "()Z")) {
                    LALTransformer.injectHead(method, new MethodInsnNode(184, HOOKS, "shouldReplaceMethod", "(Ljava/lang/Object;)Z", false), new MethodInsnNode(184, HOOKS, "replaceIsDeadOrDying", "(Ljava/lang/Object;)Z", false), new InsnNode(172));
                    methodModified = true;
                } else if (LALTransformer.isMethodDef(method, "m_6084_", "isAlive", "()Z")) {
                    LALTransformer.injectHead(method, new MethodInsnNode(184, HOOKS, "shouldReplaceMethod", "(Ljava/lang/Object;)Z", false), new MethodInsnNode(184, HOOKS, "replaceIsAlive", "(Ljava/lang/Object;)Z", false), new InsnNode(172));
                    methodModified = true;
                }
            }
            if (!methodModified) continue;
            method.maxStack += 2;
            transformedMethods.incrementAndGet();
            modified = true;
        }
        if (modified) {
            transformedClasses.incrementAndGet();
            try {
                LALAgent.markProtected(classNode.name);
            }
            catch (NoClassDefFoundError noClassDefFoundError) {
            }
        }
        return modified;
    }

    private static boolean hasTargetMethodReference(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if (TARGET_SIGS.contains(method.name + method.desc)) {
                return true;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode)insn;
                if (!TARGET_SIGS.contains(mi.name + mi.desc)) continue;
                return true;
            }
        }
        return false;
    }

    private static boolean isGetHealth(MethodInsnNode mi) {
        return ("m_21223_".equals(mi.name) || "getHealth".equals(mi.name)) && "()F".equals(mi.desc);
    }

    private static boolean isIsDeadOrDying(MethodInsnNode mi) {
        return ("m_21224_".equals(mi.name) || "isDeadOrDying".equals(mi.name)) && "()Z".equals(mi.desc);
    }

    private static boolean isIsAlive(MethodInsnNode mi) {
        return ("m_6084_".equals(mi.name) || "isAlive".equals(mi.name)) && "()Z".equals(mi.desc);
    }

    private static boolean isIsRemoved(MethodInsnNode mi) {
        return ("m_240725_".equals(mi.name) || "m_213877_".equals(mi.name) || "isRemoved".equals(mi.name)) && "()Z".equals(mi.desc);
    }

    private static boolean isGetRemovalReason(MethodInsnNode mi) {
        return ("m_146911_".equals(mi.name) || "getRemovalReason".equals(mi.name)) && "()Lnet/minecraft/world/entity/Entity$RemovalReason;".equals(mi.desc);
    }

    private static boolean isMethodDef(MethodNode method, String obfName, String name, String desc) {
        if ((method.access & 8) != 0) {
            return false;
        }
        return (obfName.equals(method.name) || name.equals(method.name)) && desc.equals(method.desc);
    }

    public static void injectHead(MethodNode method, MethodInsnNode judgeMethod, MethodInsnNode replaceMethod, InsnNode returnInsn) {
        LabelNode skipLabel = new LabelNode(new Label());
        InsnList insnList = new InsnList();
        insnList.add((AbstractInsnNode)new VarInsnNode(25, 0));
        insnList.add((AbstractInsnNode)judgeMethod);
        insnList.add((AbstractInsnNode)new JumpInsnNode(158, skipLabel));
        insnList.add((AbstractInsnNode)new VarInsnNode(25, 0));
        insnList.add((AbstractInsnNode)replaceMethod);
        insnList.add((AbstractInsnNode)returnInsn);
        insnList.add((AbstractInsnNode)skipLabel);
        method.instructions.insertBefore(method.instructions.getFirst(), insnList);
    }

    public static int getTransformedClassCount() {
        return transformedClasses.get();
    }

    public static int getTransformedMethodCount() {
        return transformedMethods.get();
    }

    public static int getSkippedClassCount() {
        return skippedClasses.get();
    }
}

