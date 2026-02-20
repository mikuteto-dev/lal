package jp.mikumiku.lal.transformer;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import jp.mikumiku.lal.agent.LALAgent;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class LALTransformer {
    private static final String HOOKS = "jp/mikumiku/lal/transformer/EntityMethodHooks";
    private static boolean initialized = false;
    private static final AtomicInteger transformedClasses = new AtomicInteger(0);
    private static final AtomicInteger transformedMethods = new AtomicInteger(0);
    private static final AtomicInteger skippedClasses = new AtomicInteger(0);


    enum HookType {
        HEAD_VOID,
        HEAD_RETURN,
        HEAD_NOCANCEL,
        CALLSITE
    }

    enum ReturnType {
        VOID(Opcodes.RETURN, -1),
        BOOLEAN(Opcodes.IRETURN, Opcodes.DUP),
        INT(Opcodes.IRETURN, Opcodes.DUP),
        FLOAT(Opcodes.FRETURN, Opcodes.DUP),
        DOUBLE(Opcodes.DRETURN, Opcodes.DUP2),
        OBJECT(Opcodes.ARETURN, Opcodes.DUP);

        final int returnOpcode;
        final int dupOpcode;

        ReturnType(int returnOpcode, int dupOpcode) {
            this.returnOpcode = returnOpcode;
            this.dupOpcode = dupOpcode;
        }
    }

    static class MethodMapping {
        final String srgName;
        final String mcpName;
        final String descriptor;
        final ReturnType returnType;
        final Set<HookType> hookTypes;
        final String headJudgeMethod;
        final String headJudgeDesc;
        final String headReplaceMethod;
        final String headReplaceDesc;
        final String headNoCancelMethod;
        final String headNoCancelDesc;
        final int headNoCancelArgSlots;
        final String callsiteHookMethod;
        final String callsiteHookDesc;

        MethodMapping(String srgName, String mcpName, String descriptor, ReturnType returnType,
                      Set<HookType> hookTypes, String headJudgeMethod, String headJudgeDesc,
                      String headReplaceMethod, String headReplaceDesc,
                      String headNoCancelMethod, String headNoCancelDesc, int headNoCancelArgSlots,
                      String callsiteHookMethod, String callsiteHookDesc) {
            this.srgName = srgName;
            this.mcpName = mcpName;
            this.descriptor = descriptor;
            this.returnType = returnType;
            this.hookTypes = hookTypes;
            this.headJudgeMethod = headJudgeMethod;
            this.headJudgeDesc = headJudgeDesc;
            this.headReplaceMethod = headReplaceMethod;
            this.headReplaceDesc = headReplaceDesc;
            this.headNoCancelMethod = headNoCancelMethod;
            this.headNoCancelDesc = headNoCancelDesc;
            this.headNoCancelArgSlots = headNoCancelArgSlots;
            this.callsiteHookMethod = callsiteHookMethod;
            this.callsiteHookDesc = callsiteHookDesc;
        }

        static class Builder {
            private final String srgName;
            private final String mcpName;
            private final String descriptor;
            private final ReturnType returnType;
            private Set<HookType> hookTypes = EnumSet.noneOf(HookType.class);
            private String headJudgeMethod, headJudgeDesc;
            private String headReplaceMethod, headReplaceDesc;
            private String headNoCancelMethod, headNoCancelDesc;
            private int headNoCancelArgSlots;
            private String callsiteHookMethod, callsiteHookDesc;

            Builder(String srg, String mcp, String desc, ReturnType ret) {
                this.srgName = srg;
                this.mcpName = mcp;
                this.descriptor = desc;
                this.returnType = ret;
            }

            Builder headVoid(String judgeMethod) {
                hookTypes.add(HookType.HEAD_VOID);
                this.headJudgeMethod = judgeMethod;
                this.headJudgeDesc = "(Ljava/lang/Object;)Z";
                return this;
            }

            Builder headVoid(String judgeMethod, String judgeDesc) {
                hookTypes.add(HookType.HEAD_VOID);
                this.headJudgeMethod = judgeMethod;
                this.headJudgeDesc = judgeDesc;
                return this;
            }

            Builder headReturn(String judgeMethod, String replaceMethod, String replaceDesc) {
                hookTypes.add(HookType.HEAD_RETURN);
                this.headJudgeMethod = judgeMethod;
                this.headJudgeDesc = "(Ljava/lang/Object;)Z";
                this.headReplaceMethod = replaceMethod;
                this.headReplaceDesc = replaceDesc;
                return this;
            }

            Builder headReturn(String judgeMethod, String judgeDesc, String replaceMethod, String replaceDesc) {
                hookTypes.add(HookType.HEAD_RETURN);
                this.headJudgeMethod = judgeMethod;
                this.headJudgeDesc = judgeDesc;
                this.headReplaceMethod = replaceMethod;
                this.headReplaceDesc = replaceDesc;
                return this;
            }

            Builder headNoCancel(String hookMethod, String hookDesc, int argSlots) {
                hookTypes.add(HookType.HEAD_NOCANCEL);
                this.headNoCancelMethod = hookMethod;
                this.headNoCancelDesc = hookDesc;
                this.headNoCancelArgSlots = argSlots;
                return this;
            }

            Builder callsite(String method, String desc) {
                hookTypes.add(HookType.CALLSITE);
                this.callsiteHookMethod = method;
                this.callsiteHookDesc = desc;
                return this;
            }

            MethodMapping build() {
                return new MethodMapping(srgName, mcpName, descriptor, returnType, hookTypes,
                        headJudgeMethod, headJudgeDesc, headReplaceMethod, headReplaceDesc,
                        headNoCancelMethod, headNoCancelDesc, headNoCancelArgSlots,
                        callsiteHookMethod, callsiteHookDesc);
            }
        }
    }

    private static final List<MethodMapping> METHOD_MAPPINGS = new ArrayList<>();
    private static final Set<String> TARGET_SIGS = new HashSet<>();
    private static final Set<String> TARGET_METHOD_NAMES = new HashSet<>();

    static {
        add(new MethodMapping.Builder("m_21223_", "getHealth", "()F", ReturnType.FLOAT)
                .headReturn("shouldReplaceMethod", "replaceGetHealth", "(Ljava/lang/Object;)F")
                .callsite("getHealth", "(Ljava/lang/Object;F)F")
                .build());

        add(new MethodMapping.Builder("m_21224_", "isDeadOrDying", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldReplaceMethod", "replaceIsDeadOrDying", "(Ljava/lang/Object;)Z")
                .callsite("isDeadOrDying", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_6084_", "isAlive", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldReplaceMethod", "replaceIsAlive", "(Ljava/lang/Object;)Z")
                .callsite("isAlive", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_213877_", "isRemoved", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldReplaceMethod", "replaceIsRemoved", "(Ljava/lang/Object;)Z")
                .callsite("isRemoved", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_146911_", "getRemovalReason", "()Lnet/minecraft/world/entity/Entity$RemovalReason;", ReturnType.OBJECT)
                .headReturn("shouldReplaceMethod", "replaceGetRemovalReason", "(Ljava/lang/Object;)Lnet/minecraft/world/entity/Entity$RemovalReason;")
                .callsite("getRemovalReason", "(Ljava/lang/Object;Lnet/minecraft/world/entity/Entity$RemovalReason;)Lnet/minecraft/world/entity/Entity$RemovalReason;")
                .build());

        add(new MethodMapping.Builder("m_6087_", "canBeCollidedWith", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockCanBeCollidedWith", "replaceCanBeCollidedWith", "(Ljava/lang/Object;)Z")
                .callsite("canBeCollidedWith", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_6863_", "isPickable", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockIsPickable", "replaceIsPickable", "(Ljava/lang/Object;)Z")
                .callsite("isPickable", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_20191_", "getBoundingBox", "()Lnet/minecraft/world/phys/AABB;", ReturnType.OBJECT)
                .headReturn("shouldBlockGetBoundingBox", "replaceGetBoundingBox", "(Ljava/lang/Object;)Lnet/minecraft/world/phys/AABB;")
                .callsite("getBoundingBox", "(Ljava/lang/Object;Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/AABB;")
                .build());

        add(new MethodMapping.Builder("m_6469_", "hurt", "(Lnet/minecraft/world/damagesource/DamageSource;F)Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockHurt", "replaceHurt", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_21165_", "removeAllEffects", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockRemoveAllEffects", "replaceRemoveAllEffects", "(Ljava/lang/Object;)Z")
                .callsite("removeAllEffects", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_7832_", "shouldDropLoot", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockShouldDropLoot", "replaceShouldDropLoot", "(Ljava/lang/Object;)Z")
                .callsite("shouldDropLoot", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_6085_", "shouldDropExperience", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockShouldDropExperience", "replaceShouldDropExperience", "(Ljava/lang/Object;)Z")
                .callsite("shouldDropExperience", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_6075_", "baseTick", "()V", ReturnType.VOID)
                .headNoCancel("onBaseTick", "(Ljava/lang/Object;)V", 0)
                .build());

        add(new MethodMapping.Builder("m_8119_", "tick", "()V", ReturnType.VOID)
                .headVoid("onLivingTickEntry")
                .build());

        add(new MethodMapping.Builder("m_20124_", "setPose", "(Lnet/minecraft/world/entity/Pose;)V", ReturnType.VOID)
                .headVoid("shouldBlockSetPose", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_142687_", "setRemoved", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", ReturnType.VOID)
                .headVoid("shouldBlockSetRemoved")
                .build());

        add(new MethodMapping.Builder("m_6074_", "kill", "()V", ReturnType.VOID)
                .headVoid("shouldBlockKill")
                .build());

        add(new MethodMapping.Builder("m_142036_", "discard", "()V", ReturnType.VOID)
                .headVoid("shouldBlockDiscard")
                .build());

        add(new MethodMapping.Builder("m_142467_", "remove", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", ReturnType.VOID)
                .headVoid("shouldBlockRemove")
                .build());

        add(new MethodMapping.Builder("m_6091_", "move", "(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", ReturnType.VOID)
                .headVoid("shouldBlockMove")
                .build());

        add(new MethodMapping.Builder("m_20344_", "setPosRaw", "(DDD)V", ReturnType.VOID)
                .headVoid("shouldBlockSetPosRaw")
                .build());

        add(new MethodMapping.Builder("m_20257_", "setDeltaMovement", "(Lnet/minecraft/world/phys/Vec3;)V", ReturnType.VOID)
                .headVoid("shouldBlockSetDeltaMovement", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_5765_", "push", "(DDD)V", ReturnType.VOID)
                .headVoid("shouldBlockPush")
                .build());

        add(new MethodMapping.Builder("m_6667_", "die", "(Lnet/minecraft/world/damagesource/DamageSource;)V", ReturnType.VOID)
                .headVoid("shouldBlockDie")
                .build());

        add(new MethodMapping.Builder("m_21154_", "setHealth", "(F)V", ReturnType.VOID)
                .headVoid("shouldBlockSetHealth")
                .build());

        add(new MethodMapping.Builder("m_21230_", "tickDeath", "()V", ReturnType.VOID)
                .headVoid("shouldBlockTickDeath")
                .build());

        add(new MethodMapping.Builder("m_6550_", "actuallyHurt", "(Lnet/minecraft/world/damagesource/DamageSource;F)V", ReturnType.VOID)
                .headVoid("shouldBlockActuallyHurt")
                .build());

        add(new MethodMapping.Builder("m_6660_", "knockback", "(DDD)V", ReturnType.VOID)
                .headVoid("shouldBlockKnockback")
                .build());

        add(new MethodMapping.Builder("m_20259_", "setNoGravity", "(Z)V", ReturnType.VOID)
                .headVoid("shouldBlockSetNoGravity")
                .build());

        add(new MethodMapping.Builder("m_7967_", "addFreshEntity", "(Lnet/minecraft/world/entity/Entity;)Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockAddFreshEntity", "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        "replaceHurt", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_8793_", "tick", "(Ljava/util/function/BooleanSupplier;)V", ReturnType.VOID)
                .headNoCancel("onServerTick", "(Ljava/lang/Object;)V", 0)
                .build());
    }

    private static void add(MethodMapping mapping) {
        METHOD_MAPPINGS.add(mapping);
        TARGET_SIGS.add(mapping.srgName + mapping.descriptor);
        TARGET_SIGS.add(mapping.mcpName + mapping.descriptor);
        TARGET_METHOD_NAMES.add(mapping.srgName);
        TARGET_METHOD_NAMES.add(mapping.mcpName);
    }

    public LALTransformer() {
        super();
    }

    public static void initialize() {
        if (initialized) return;
        initialized = true;
    }

    public static boolean transform(ClassNode classNode) {
        return transform(classNode, null);
    }

    public static boolean transform(ClassNode classNode, ILaunchPluginService.Phase phase) {
        if (classNode.name.startsWith("jp/mikumiku/lal/transformer")) return false;

        boolean hasMethodRef = hasTargetMethodReference(classNode);
        boolean hasFieldRef = hasEntityLookupFieldReference(classNode);
        if (!hasMethodRef && !hasFieldRef) {
            skippedClasses.incrementAndGet();
            return false;
        }

        boolean doHead = phase == null || phase == ILaunchPluginService.Phase.BEFORE;
        boolean doReturn = phase == null || phase == ILaunchPluginService.Phase.AFTER;
        boolean modified = false;

        for (MethodNode method : classNode.methods) {
            boolean methodModified = false;

            if (doReturn) {
                if (hasMethodRef) {
                    methodModified |= processCallsites(method);
                    methodModified |= processReturnHooks(method);
                }
                if (hasFieldRef) {
                    methodModified |= processEntityLookupFields(method);
                }
            }

            if (doHead && hasMethodRef) {
                methodModified |= processHeadInjection(method);
            }

            if (methodModified) {
                method.maxStack += 4;
                transformedMethods.incrementAndGet();
                modified = true;
            }
        }

        if (modified) {
            transformedClasses.incrementAndGet();
            try {
                LALAgent.markProtected(classNode.name);
            } catch (NoClassDefFoundError ignored) {}
        }
        return modified;
    }

    private static boolean processCallsites(MethodNode method) {
        if (method.instructions.size() == 0) return false;
        boolean modified = false;
        ArrayList<MethodInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode mi = (MethodInsnNode) insn;
            int opcode = mi.getOpcode();
            if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) continue;
            String sig = mi.name + mi.desc;
            if (!TARGET_SIGS.contains(sig)) continue;
            MethodMapping mapping = findMappingForCallsite(mi.name, mi.desc);
            if (mapping == null || !mapping.hookTypes.contains(HookType.CALLSITE)) continue;
            if (mapping.callsiteHookMethod == null) continue;
            targets.add(mi);
        }
        for (MethodInsnNode mi : targets) {
            MethodMapping mapping = findMappingForCallsite(mi.name, mi.desc);
            if (mapping == null) continue;
            method.instructions.insertBefore(mi, new InsnNode(Opcodes.DUP));
            method.instructions.insert(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    mapping.callsiteHookMethod, mapping.callsiteHookDesc, false));
            modified = true;
        }
        return modified;
    }

    private static boolean processReturnHooks(MethodNode method) {
        if (method.instructions.size() == 0) return false;
        boolean modified = false;
        MethodMapping mapping = findMappingForMethodDef(method);
        if (mapping == null) return false;
        if (!mapping.hookTypes.contains(HookType.CALLSITE) && !mapping.hookTypes.contains(HookType.HEAD_RETURN)) return false;

        int targetOpcode = mapping.returnType.returnOpcode;
        if (targetOpcode == Opcodes.RETURN) return false;

        String returnWrapperMethod = mapping.callsiteHookMethod;
        if (returnWrapperMethod == null) return false;

        String returnWrapperDesc = buildReturnWrapperDesc(mapping);
        if (returnWrapperDesc == null) return false;

        ArrayList<AbstractInsnNode> returnInsns = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == targetOpcode) {
                returnInsns.add(insn);
            }
        }
        for (AbstractInsnNode insn : returnInsns) {
            InsnList patch = new InsnList();
            patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    returnWrapperMethod, returnWrapperDesc, false));
            method.instructions.insertBefore(insn, patch);
            modified = true;
        }
        return modified;
    }

    private static String buildReturnWrapperDesc(MethodMapping mapping) {
        switch (mapping.returnType) {
            case FLOAT:   return "(FLjava/lang/Object;)F";
            case BOOLEAN: return "(ZLjava/lang/Object;)Z";
            case DOUBLE:  return "(DLjava/lang/Object;)D";
            case OBJECT: {
                String desc = mapping.descriptor;
                String retType = desc.substring(desc.lastIndexOf(')') + 1);
                return "(" + retType + "Ljava/lang/Object;)" + retType;
            }
            default: return null;
        }
    }

    private static boolean processHeadInjection(MethodNode method) {
        MethodMapping mapping = findMappingForMethodDef(method);
        if (mapping == null) return false;

        if (mapping.hookTypes.contains(HookType.HEAD_RETURN)) {
            return injectHeadReturn(method, mapping);
        } else if (mapping.hookTypes.contains(HookType.HEAD_VOID)) {
            return injectHeadVoid(method, mapping);
        } else if (mapping.hookTypes.contains(HookType.HEAD_NOCANCEL)) {
            return injectHeadNoCancel(method, mapping);
        }
        return false;
    }

    private static boolean injectHeadReturn(MethodNode method, MethodMapping mapping) {
        if (mapping.headJudgeMethod == null || mapping.headReplaceMethod == null) return false;
        if (method.instructions.size() == 0 || method.instructions.getFirst() == null) return false;

        LabelNode skipLabel = new LabelNode(new Label());
        InsnList patch = new InsnList();

        if (mapping.headJudgeDesc != null && mapping.headJudgeDesc.contains("Ljava/lang/Object;Ljava/lang/Object;")) {
            patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    mapping.headJudgeMethod, mapping.headJudgeDesc, false));
        } else {
            patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    mapping.headJudgeMethod, mapping.headJudgeDesc != null ? mapping.headJudgeDesc : "(Ljava/lang/Object;)Z", false));
        }

        patch.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                mapping.headReplaceMethod, mapping.headReplaceDesc, false));
        patch.add(new InsnNode(mapping.returnType.returnOpcode));
        patch.add(skipLabel);

        method.instructions.insertBefore(method.instructions.getFirst(), patch);
        return true;
    }

    private static boolean injectHeadVoid(MethodNode method, MethodMapping mapping) {
        if (mapping.headJudgeMethod == null) return false;
        if (method.instructions.size() == 0 || method.instructions.getFirst() == null) return false;

        LabelNode skipLabel = new LabelNode(new Label());
        InsnList patch = new InsnList();

        if (mapping.headJudgeDesc != null && mapping.headJudgeDesc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Z")) {
            patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    mapping.headJudgeMethod, mapping.headJudgeDesc, false));
        } else {
            patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    mapping.headJudgeMethod, mapping.headJudgeDesc != null ? mapping.headJudgeDesc : "(Ljava/lang/Object;)Z", false));
        }

        patch.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
        patch.add(new InsnNode(Opcodes.RETURN));
        patch.add(skipLabel);

        method.instructions.insertBefore(method.instructions.getFirst(), patch);
        return true;
    }

    private static boolean injectHeadNoCancel(MethodNode method, MethodMapping mapping) {
        if (mapping.headNoCancelMethod == null) return false;
        if (method.instructions.size() == 0 || method.instructions.getFirst() == null) return false;

        InsnList patch = new InsnList();
        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));

        if (mapping.headNoCancelArgSlots > 0) {
            patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
        }

        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                mapping.headNoCancelMethod, mapping.headNoCancelDesc, false));

        method.instructions.insertBefore(method.instructions.getFirst(), patch);
        return true;
    }

    private static MethodMapping findMappingForMethodDef(MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) != 0) return null;
        if ((method.access & Opcodes.ACC_ABSTRACT) != 0) return null;
        if ((method.access & Opcodes.ACC_NATIVE) != 0) return null;
        for (MethodMapping mapping : METHOD_MAPPINGS) {
            if (mapping.descriptor.equals(method.desc)
                    && (mapping.srgName.equals(method.name) || mapping.mcpName.equals(method.name))) {
                return mapping;
            }
        }
        return null;
    }

    private static MethodMapping findMappingForCallsite(String name, String desc) {
        for (MethodMapping mapping : METHOD_MAPPINGS) {
            if (mapping.descriptor.equals(desc)
                    && (mapping.srgName.equals(name) || mapping.mcpName.equals(name))) {
                return mapping;
            }
        }
        return null;
    }

    private static boolean hasTargetMethodReference(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if (TARGET_METHOD_NAMES.contains(method.name) && TARGET_SIGS.contains(method.name + method.desc)) {
                return true;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (!TARGET_METHOD_NAMES.contains(mi.name)) continue;
                if (TARGET_SIGS.contains(mi.name + mi.desc)) return true;
            }
        }
        return false;
    }

    private static final Set<String> ENTITY_LOOKUP_FIELD_NAMES = Set.of(
            "byId", "f_156816_", "f_156807_",
            "byUuid", "f_156817_", "f_156808_"
    );

    private static boolean hasEntityLookupFieldReference(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof FieldInsnNode)) continue;
                FieldInsnNode fi = (FieldInsnNode) insn;
                if (fi.getOpcode() != Opcodes.GETFIELD) continue;
                if (ENTITY_LOOKUP_FIELD_NAMES.contains(fi.name) &&
                    fi.owner.contains("EntityLookup")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean processEntityLookupFields(MethodNode method) {
        if (method.instructions.size() == 0) return false;
        boolean modified = false;
        ArrayList<FieldInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode)) continue;
            FieldInsnNode fi = (FieldInsnNode) insn;
            if (fi.getOpcode() != Opcodes.GETFIELD) continue;
            if (!fi.owner.contains("EntityLookup")) continue;
            if (ENTITY_LOOKUP_FIELD_NAMES.contains(fi.name)) {
                targets.add(fi);
            }
        }
        for (FieldInsnNode fi : targets) {
            String name = fi.name;
            String fieldDesc = fi.desc;
            if (name.equals("byId") || name.equals("f_156816_") || name.equals("f_156807_")) {
                MethodInsnNode replacement = new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                        "getFilteredById",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                method.instructions.set(fi, replacement);
                String castType = fieldDesc.startsWith("L") ? fieldDesc.substring(1, fieldDesc.length() - 1) : fieldDesc;
                method.instructions.insert(replacement, new TypeInsnNode(Opcodes.CHECKCAST, castType));
                modified = true;
            } else if (name.equals("byUuid") || name.equals("f_156817_") || name.equals("f_156808_")) {
                MethodInsnNode replacement = new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                        "getFilteredByUuid",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                method.instructions.set(fi, replacement);
                String castType = fieldDesc.startsWith("L") ? fieldDesc.substring(1, fieldDesc.length() - 1) : fieldDesc;
                method.instructions.insert(replacement, new TypeInsnNode(Opcodes.CHECKCAST, castType));
                modified = true;
            }
        }
        return modified;
    }

    public static int getTransformedClassCount() { return transformedClasses.get(); }
    public static int getTransformedMethodCount() { return transformedMethods.get(); }
    public static int getSkippedClassCount() { return skippedClasses.get(); }

    public static void injectHead(MethodNode method, MethodInsnNode judgeMethod, MethodInsnNode replaceMethod, InsnNode returnInsn) {
        LabelNode skipLabel = new LabelNode(new Label());
        InsnList insnList = new InsnList();
        insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insnList.add(judgeMethod);
        insnList.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
        insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insnList.add(replaceMethod);
        insnList.add(returnInsn);
        insnList.add(skipLabel);
        method.instructions.insertBefore(method.instructions.getFirst(), insnList);
    }
}
