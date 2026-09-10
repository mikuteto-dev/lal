package jp.mikumiku.lal.transformer;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import jp.mikumiku.lal.agent.LALAgent;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public class LALTransformer {
    private static final String HOOKS = "jp/mikumiku/lal/transformer/EntityMethodHooks";

    /**
     * Covers the widest injected prologue: receiver plus three doubles (7 slots), when the writer
     * does not recompute maxs.
     */
    private static final int INJECTED_STACK_HEADROOM = 8;

    private static boolean initialized = false;
    private static final AtomicInteger transformedClasses = new AtomicInteger(0);
    private static final AtomicInteger transformedMethods = new AtomicInteger(0);
    private static final AtomicInteger skippedClasses = new AtomicInteger(0);
    private static final java.util.concurrent.ConcurrentHashMap<String, Set<String>> HEAD_INJECTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Computed once per class: LALPlugin asked for both phases and each phase re-scanned every
     * instruction of every method, with a result that cannot differ between them.
     */
    private static final int SCAN_METHOD_REF = 1;
    private static final int SCAN_FIELD_REF = 2;
    private static final int SCAN_CACHE_MAX = 200_000;
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> SCAN_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int scanFlags(ClassNode classNode, boolean isMinecraftClass) {
        Integer cached = SCAN_CACHE.get(classNode.name);
        if (cached != null) return cached;
        int flags = 0;
        if (hasTargetMethodReference(classNode, isMinecraftClass)) flags |= SCAN_METHOD_REF;
        if (hasEntityLookupFieldReference(classNode)) flags |= SCAN_FIELD_REF;
        if (SCAN_CACHE.size() < SCAN_CACHE_MAX) {
            SCAN_CACHE.put(classNode.name, flags);
        }
        return flags;
    }


    enum HookType {
        HEAD_VOID,
        HEAD_RETURN,
        HEAD_NOCANCEL,
        CALLSITE,
        TAIL_NOCANCEL
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
        final String tailNoCancelMethod;
        final String tailNoCancelDesc;
        final int tailNoCancelArgSlots;

        MethodMapping(String srgName, String mcpName, String descriptor, ReturnType returnType,
                      Set<HookType> hookTypes, String headJudgeMethod, String headJudgeDesc,
                      String headReplaceMethod, String headReplaceDesc,
                      String headNoCancelMethod, String headNoCancelDesc, int headNoCancelArgSlots,
                      String callsiteHookMethod, String callsiteHookDesc,
                      String tailNoCancelMethod, String tailNoCancelDesc, int tailNoCancelArgSlots) {
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
            this.tailNoCancelMethod = tailNoCancelMethod;
            this.tailNoCancelDesc = tailNoCancelDesc;
            this.tailNoCancelArgSlots = tailNoCancelArgSlots;
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
            private String tailNoCancelMethod, tailNoCancelDesc;
            private int tailNoCancelArgSlots;

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

            Builder tailNoCancel(String hookMethod, String hookDesc, int argSlots) {
                hookTypes.add(HookType.TAIL_NOCANCEL);
                this.tailNoCancelMethod = hookMethod;
                this.tailNoCancelDesc = hookDesc;
                this.tailNoCancelArgSlots = argSlots;
                return this;
            }

            MethodMapping build() {
                return new MethodMapping(srgName, mcpName, descriptor, returnType, hookTypes,
                        headJudgeMethod, headJudgeDesc, headReplaceMethod, headReplaceDesc,
                        headNoCancelMethod, headNoCancelDesc, headNoCancelArgSlots,
                        callsiteHookMethod, callsiteHookDesc,
                        tailNoCancelMethod, tailNoCancelDesc, tailNoCancelArgSlots);
            }
        }
    }

    private static final List<MethodMapping> METHOD_MAPPINGS = new ArrayList<>();
    private static final Set<String> TARGET_SIGS = new HashSet<>();
    private static final Set<String> TARGET_SRG_SIGS = new HashSet<>();

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

        add(new MethodMapping.Builder("m_5829_", "canBeCollidedWith", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockCanBeCollidedWith", "replaceCanBeCollidedWith", "(Ljava/lang/Object;)Z")
                .callsite("canBeCollidedWith", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_6087_", "isPickable", "()Z", ReturnType.BOOLEAN)
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

        add(new MethodMapping.Builder("m_21219_", "removeAllEffects", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockRemoveAllEffects", "replaceRemoveAllEffects", "(Ljava/lang/Object;)Z")
                .callsite("removeAllEffects", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_6125_", "shouldDropLoot", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockShouldDropLoot", "replaceShouldDropLoot", "(Ljava/lang/Object;)Z")
                .callsite("shouldDropLoot", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_6149_", "shouldDropExperience", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockShouldDropExperience", "replaceShouldDropExperience", "(Ljava/lang/Object;)Z")
                .callsite("shouldDropExperience", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_5706_", "attack", "(Lnet/minecraft/world/entity/Entity;)V", ReturnType.VOID)
                .headNoCancel("onAttack", "(Ljava/lang/Object;Ljava/lang/Object;)V", 1)
                .build());

        add(new MethodMapping.Builder("m_6075_", "baseTick", "()V", ReturnType.VOID)
                .headNoCancel("onBaseTick", "(Ljava/lang/Object;)V", 0)
                .build());

        add(new MethodMapping.Builder("m_8119_", "tick", "()V", ReturnType.VOID)
                .headVoid("onLivingTickEntry")
                .tailNoCancel("onLivingTickTail", "(Ljava/lang/Object;)V", 0)
                .build());

        add(new MethodMapping.Builder("m_21515_", "isEffectiveAi", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockMobAi", "(Ljava/lang/Object;)Z",
                        "replaceIsEffectiveAiFalse", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_20124_", "setPose", "(Lnet/minecraft/world/entity/Pose;)V", ReturnType.VOID)
                .headVoid("shouldBlockSetPose", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_142467_", "setRemoved", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", ReturnType.VOID)
                .headVoid("shouldBlockSetRemoved")
                .build());

        add(new MethodMapping.Builder("m_6074_", "kill", "()V", ReturnType.VOID)
                .headVoid("shouldBlockKill")
                .build());

        add(new MethodMapping.Builder("m_146870_", "discard", "()V", ReturnType.VOID)
                .headVoid("shouldBlockDiscard")
                .build());

        add(new MethodMapping.Builder("m_142687_", "remove", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", ReturnType.VOID)
                .headVoid("shouldBlockRemove")
                .build());

        add(new MethodMapping.Builder("m_6478_", "move", "(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", ReturnType.VOID)
                .headVoid("shouldBlockMove")
                .build());

        add(new MethodMapping.Builder("m_20343_", "setPosRaw", "(DDD)V", ReturnType.VOID)
                .headVoid("shouldBlockSetPosRaw", "(Ljava/lang/Object;DDD)Z")
                .build());

        add(new MethodMapping.Builder("m_20256_", "setDeltaMovement", "(Lnet/minecraft/world/phys/Vec3;)V", ReturnType.VOID)
                .headVoid("shouldBlockSetDeltaMovement", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_5997_", "push", "(DDD)V", ReturnType.VOID)
                .headVoid("shouldBlockPush")
                .build());

        add(new MethodMapping.Builder("m_6667_", "die", "(Lnet/minecraft/world/damagesource/DamageSource;)V", ReturnType.VOID)
                .headVoid("shouldBlockDie")
                .build());

        add(new MethodMapping.Builder("m_21153_", "setHealth", "(F)V", ReturnType.VOID)
                .headVoid("shouldBlockSetHealth")
                .build());

        add(new MethodMapping.Builder("m_6153_", "tickDeath", "()V", ReturnType.VOID)
                .headVoid("shouldBlockTickDeath")
                .build());

        add(new MethodMapping.Builder("m_6475_", "actuallyHurt", "(Lnet/minecraft/world/damagesource/DamageSource;F)V", ReturnType.VOID)
                .headVoid("shouldBlockActuallyHurt")
                .build());

        add(new MethodMapping.Builder("m_147240_", "knockback", "(DDD)V", ReturnType.VOID)
                .headVoid("shouldBlockKnockback")
                .build());

        add(new MethodMapping.Builder("m_20242_", "setNoGravity", "(Z)V", ReturnType.VOID)
                .headVoid("shouldBlockSetNoGravity")
                .build());

        add(new MethodMapping.Builder("m_7967_", "addFreshEntity", "(Lnet/minecraft/world/entity/Entity;)Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockAddFreshEntity", "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        "replaceHurt", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_8793_", "tick", "(Ljava/util/function/BooleanSupplier;)V", ReturnType.VOID)
                .headNoCancel("onServerTick", "(Ljava/lang/Object;)V", 0)
                .tailNoCancel("onServerLevelTickTail", "(Ljava/lang/Object;)V", 0)
                .build());

        add(new MethodMapping.Builder("m_46653_", "guardEntityTick",
                "(Ljava/util/function/Consumer;Lnet/minecraft/world/entity/Entity;)V", ReturnType.VOID)
                // Local 1 is the Consumer and local 2 the entity, so 1 made the hook's
                // instanceof check always fail.
                .headNoCancel("onGuardEntityTick", "(Ljava/lang/Object;Ljava/lang/Object;)V", 2)
                .build());

        add(new MethodMapping.Builder("m_142391_", "shouldBeSaved", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockShouldBeSaved", "replaceShouldBeSaved", "(Ljava/lang/Object;)Z")
                .callsite("shouldBeSaved", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_21233_", "getMaxHealth", "()F", ReturnType.FLOAT)
                .headReturn("shouldBlockGetMaxHealth", "replaceGetMaxHealth", "(Ljava/lang/Object;)F")
                .callsite("getMaxHealth", "(Ljava/lang/Object;F)F")
                .build());

        add(new MethodMapping.Builder("m_220157_", "hurt",
                "(ILnet/minecraft/util/RandomSource;Lnet/minecraft/server/level/ServerPlayer;)Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockItemStackHurt", "(Ljava/lang/Object;)Z",
                        "replaceItemStackHurt", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_141960_", "setLevelCallback",
                "(Lnet/minecraft/world/level/entity/EntityInLevelCallback;)V", ReturnType.VOID)
                .headVoid("shouldBlockSetLevelCallback", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_157557_", "addEntityUuid",
                "(Lnet/minecraft/world/level/entity/EntityAccess;)Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockAddEntityUuid", "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        "replaceHurt", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_188355_", "remove",
                "(Lnet/minecraft/world/level/entity/EntityAccess;)Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockEntitySectionRemove", "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        "replaceHurt", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_156912_", "remove",
                "(Lnet/minecraft/world/entity/Entity;)V", ReturnType.VOID)
                .headVoid("shouldBlockEntityTickListRemove", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_142472_", "onRemove",
                "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", ReturnType.VOID)
                .headVoid("shouldBlockCallbackOnRemove", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_157580_", "stopTracking",
                "(Lnet/minecraft/world/level/entity/EntityAccess;)V", ReturnType.VOID)
                .headVoid("shouldBlockStopTracking", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_135381_", "set",
                "(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V", ReturnType.VOID)
                .headVoid("shouldBlockSynchedDataSet", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_135397_", "setValue",
                "(Ljava/lang/Object;)V", ReturnType.VOID)
                .headVoid("shouldBlockDataItemSetValue", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_135401_", "setDirty",
                "(Z)V", ReturnType.VOID)
                .headVoid("shouldBlockDataItemSetDirty", "(Ljava/lang/Object;Z)Z")
                .build());

        add(new MethodMapping.Builder("m_142747_", "handlePlayerCombatKill",
                "(Lnet/minecraft/network/protocol/game/ClientboundPlayerCombatKillPacket;)V", ReturnType.VOID)
                .headVoid("shouldBlockHandlePlayerCombatKill", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_21317_", "setArrowCount", "(I)V", ReturnType.VOID)
                .headVoid("shouldBlockSetArrowCount")
                .build());

        add(new MethodMapping.Builder("m_109599_", "renderLevel",
                "(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
                ReturnType.VOID)
                .tailNoCancel("onRenderLevelTail", "(Ljava/lang/Object;)V", 0)
                .build());

        add(new MethodMapping.Builder("m_109089_", "renderLevel",
                "(FJLcom/mojang/blaze3d/vertex/PoseStack;)V", ReturnType.VOID)
                .tailNoCancel("onGameRendererRenderLevelTail", "(Ljava/lang/Object;)V", 0)
                .build());

        add(new MethodMapping.Builder("revive", "revive", "()V", ReturnType.VOID)
                .headVoid("shouldBlockRevive")
                .build());

        add(new MethodMapping.Builder("reviveCaps", "reviveCaps", "()V", ReturnType.VOID)
                .headVoid("shouldBlockReviveCaps")
                .build());

        add(new MethodMapping.Builder("m_6043_", "checkDespawn", "()V", ReturnType.VOID)
                .headVoid("shouldBlockCheckDespawn")
                .build());

        add(new MethodMapping.Builder("m_7041_", "stopServer", "()V", ReturnType.VOID)
                .headNoCancel("onServerStopping", "(Ljava/lang/Object;)V", 0)
                .build());

        add(new MethodMapping.Builder("m_11261_", "placeNewPlayer",
                "(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;)V", ReturnType.VOID)
                .tailNoCancel("onPlayerJoined", "(Ljava/lang/Object;Ljava/lang/Object;)V", 2)
                .build());

        add(new MethodMapping.Builder("m_7026_", "onDisconnect",
                "(Lnet/minecraft/network/chat/Component;)V", ReturnType.VOID)
                .headNoCancel("onPlayerDisconnect", "(Ljava/lang/Object;)V", 0)
                .build());

        add(new MethodMapping.Builder("m_8872_", "addEntity",
                "(Lnet/minecraft/world/entity/Entity;)Z", ReturnType.BOOLEAN)
                .headNoCancel("onEntityAddedToLevel", "(Ljava/lang/Object;Ljava/lang/Object;)V", 1)
                .build());

        add(new MethodMapping.Builder("m_5790_", "onHitEntity",
                "(Lnet/minecraft/world/phys/EntityHitResult;)V", ReturnType.VOID)
                .headNoCancel("onArrowHitEntity", "(Ljava/lang/Object;Ljava/lang/Object;)V", 1)
                .build());

        add(new MethodMapping.Builder("m_6785_", "removeWhenFarAway", "(D)Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockRemoveWhenFarAway", "replaceRemoveWhenFarAway", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_8028_", "shouldDespawnInPeaceful", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockShouldDespawnInPeaceful", "replaceShouldDespawnInPeaceful", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_21532_", "isPersistenceRequired", "()Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockIsPersistenceRequired", "replaceIsPersistenceRequired", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_6096_", "interact",
                "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
                ReturnType.OBJECT)
                .headReturn("shouldBlockInteract", "(Ljava/lang/Object;)Z",
                        "replaceInteractFail", "(Ljava/lang/Object;)Lnet/minecraft/world/InteractionResult;")
                .build());

        add(new MethodMapping.Builder("m_6071_", "mobInteract",
                "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
                ReturnType.OBJECT)
                .headReturn("shouldBlockInteract", "(Ljava/lang/Object;)Z",
                        "replaceInteractFail", "(Ljava/lang/Object;)Lnet/minecraft/world/InteractionResult;")
                .build());

        add(new MethodMapping.Builder("m_7111_", "interactAt",
                "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
                ReturnType.OBJECT)
                .headReturn("shouldBlockInteract", "(Ljava/lang/Object;)Z",
                        "replaceInteractFail", "(Ljava/lang/Object;)Lnet/minecraft/world/InteractionResult;")
                .build());

        add(new MethodMapping.Builder("m_5634_", "heal", "(F)V", ReturnType.VOID)
                .headVoid("shouldBlockHeal")
                .build());

        add(new MethodMapping.Builder("m_7292_", "addEffect",
                "(Lnet/minecraft/world/effect/MobEffectInstance;)Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockAddEffect", "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        "replaceAddEffectFalse", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_147207_", "addEffect",
                "(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", ReturnType.BOOLEAN)
                .headReturn("shouldBlockAddEffect", "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        "replaceAddEffectFalse", "(Ljava/lang/Object;)Z")
                .build());

        add(new MethodMapping.Builder("m_7911_", "setAbsorptionAmount", "(F)V", ReturnType.VOID)
                .headVoid("shouldBlockSetAbsorptionAmount")
                .build());

        add(new MethodMapping.Builder("m_20331_", "setInvulnerable", "(Z)V", ReturnType.VOID)
                .headVoid("shouldBlockSetInvulnerable")
                .build());


    }

    private static void add(MethodMapping mapping) {
        METHOD_MAPPINGS.add(mapping);
        TARGET_SIGS.add(mapping.srgName + mapping.descriptor);
        TARGET_SIGS.add(mapping.mcpName + mapping.descriptor);
        // The runtime name only appears on vanilla members, so it is safe to match anywhere.
        TARGET_SRG_SIGS.add(mapping.srgName + mapping.descriptor);
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
        if (classNode.name.startsWith("jp/mikumiku/lal/agent")) return false;

        boolean doHead = phase == null || phase == ILaunchPluginService.Phase.BEFORE;
        boolean doReturn = phase == null || phase == ILaunchPluginService.Phase.AFTER;
        boolean modified = false;

        if (doHead && (classNode.name.equals("net/minecraft/server/level/ServerLevel")
                || classNode.name.equals("net/minecraft/server/level/ServerLevel"))) {
            modified |= injectServerLevelHelpers(classNode);
        }

        if (doHead && !classNode.name.startsWith("jp/mikumiku/lal/")) {
            modified |= processSelfDefense(classNode);
        }

        // Definition hooks replace a vanilla body, and matching is only name+descriptor, so without
        // this a mod class that merely declares tick()/getHealth()/setDirty(Z)V gets entity semantics.
        boolean isMinecraftClass = classNode.name.startsWith("net/minecraft/");
        int scan = scanFlags(classNode, isMinecraftClass);
        boolean hasMethodRef = (scan & SCAN_METHOD_REF) != 0;
        boolean hasFieldRef = (scan & SCAN_FIELD_REF) != 0;
        if (!hasMethodRef && !hasFieldRef) {
            if (!modified) {
                skippedClasses.incrementAndGet();
                return false;
            }
            transformedClasses.incrementAndGet();
            try { LALAgent.markProtected(classNode.name); } catch (NoClassDefFoundError ignored) {}
            return true;
        }

        for (MethodNode method : classNode.methods) {
            boolean methodModified = false;

            if (doReturn) {
                if (hasMethodRef) {
                    // Enabled for every class: a mod reading a vanilla getter still needs this.
                    methodModified |= processCallsites(method);
                    if (isMinecraftClass) {
                        methodModified |= processReturnHooks(method);
                    }
                }
                if (hasFieldRef) {
                    methodModified |= processEntityLookupFields(method);
                }
            }

            if (doHead && hasMethodRef && isMinecraftClass) {
                boolean headInjected = processHeadInjection(method);
                methodModified |= headInjected;
                methodModified |= processTailInjection(method);
                if (headInjected) {
                    HEAD_INJECTED.computeIfAbsent(classNode.name, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                            .add(method.name + method.desc);
                }
            }

            if (methodModified) {
                method.maxStack += INJECTED_STACK_HEADROOM;
                transformedMethods.incrementAndGet();
                modified = true;
            }
        }

        if (doReturn && !doHead) {
            Set<String> expected = HEAD_INJECTED.get(classNode.name);
            if (expected != null && !expected.isEmpty()) {
                for (String methodKey : expected) {
                    boolean found = false;
                    for (MethodNode method : classNode.methods) {
                        if ((method.name + method.desc).equals(methodKey)) {
                            found = hasHooksCall(method);
                            break;
                        }
                    }
                    if (!found) {
                        for (MethodNode method : classNode.methods) {
                            if ((method.name + method.desc).equals(methodKey)) {
                                if (processHeadInjection(method)) {
                                    method.maxStack += INJECTED_STACK_HEADROOM;
                                    modified = true;
                                }
                                break;
                            }
                        }
                    }
                }
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

    private static boolean hasHooksCall(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                if (HOOKS.equals(mi.owner)) return true;
            }
        }
        return false;
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
            if (!isVanillaOwner(mi.owner)) continue;
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

    /**
     * A call whose owner is a mod class that merely shares the name and descriptor must not be
     * wrapped: the hook descriptors assume the vanilla receiver.
     */
    private static boolean isVanillaOwner(String internalName) {
        return internalName != null && internalName.startsWith("net/minecraft/");
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
            // The judge wants the first argument, so a no-argument method cannot supply it.
            if (argSlots(method) < 1) return false;
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

        String desc = mapping.headJudgeDesc != null ? mapping.headJudgeDesc : "(Ljava/lang/Object;)Z";
        LabelNode skipLabel = new LabelNode(new Label());
        InsnList patch = new InsnList();

        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (!pushHeadVoidOperands(patch, method, desc)) return false;
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, mapping.headJudgeMethod, desc, false));

        patch.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
        patch.add(new InsnNode(Opcodes.RETURN));
        patch.add(skipLabel);

        method.instructions.insertBefore(method.instructions.getFirst(), patch);
        return true;
    }

    /**
     * Operands come from the hook's own descriptor. Hand-written branches missed (Object;Z) and
     * emitted a call with a missing operand, which the verifier rejects at class load. Returns
     * false when the method cannot supply what the hook wants, so a bad table entry degrades to
     * "not applied".
     */
    private static boolean pushHeadVoidOperands(InsnList patch, MethodNode method, String hookDesc) {
        final String receiver = "(Ljava/lang/Object;";
        if (!hookDesc.startsWith(receiver) || !hookDesc.endsWith(")Z")) return false;
        String params = hookDesc.substring(receiver.length(), hookDesc.length() - 2);
        Type[] wanted = params.isEmpty() ? new Type[0] : Type.getArgumentTypes("(" + params + ")V");
        if (wanted.length == 0) return true;

        Type[] actual = Type.getArgumentTypes(method.desc);
        if (actual.length != wanted.length) return false;

        int slot = 1;
        for (int i = 0; i < actual.length; i++) {
            // Compare load categories: Object accepts any reference, and ILOAD covers
            // boolean/byte/char/short.
            if (wanted[i].getOpcode(Opcodes.ILOAD) != actual[i].getOpcode(Opcodes.ILOAD)) return false;
            patch.add(new VarInsnNode(actual[i].getOpcode(Opcodes.ILOAD), slot));
            slot += actual[i].getSize();
        }
        return true;
    }

    private static boolean injectHeadNoCancel(MethodNode method, MethodMapping mapping) {
        if (mapping.headNoCancelMethod == null) return false;
        if (method.instructions.size() == 0 || method.instructions.getFirst() == null) return false;
        if (mapping.headNoCancelArgSlots > 0 && mapping.headNoCancelArgSlots > argSlots(method)) return false;

        InsnList patch = new InsnList();
        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));

        if (mapping.headNoCancelArgSlots > 0) {
            patch.add(new VarInsnNode(Opcodes.ALOAD, mapping.headNoCancelArgSlots));
        }

        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                mapping.headNoCancelMethod, mapping.headNoCancelDesc, false));

        method.instructions.insertBefore(method.instructions.getFirst(), patch);
        return true;
    }

        private static int argSlots(MethodNode method) {
        int slots = 0;
        for (Type t : Type.getArgumentTypes(method.desc)) {
            slots += t.getSize();
        }
        return slots;
    }

    private static boolean processTailInjection(MethodNode method) {
        MethodMapping mapping = findMappingForMethodDef(method);
        if (mapping == null) return false;
        if (!mapping.hookTypes.contains(HookType.TAIL_NOCANCEL)) return false;
        return injectTailNoCancel(method, mapping);
    }

    private static boolean injectTailNoCancel(MethodNode method, MethodMapping mapping) {
        if (mapping.tailNoCancelMethod == null) return false;
        if (method.instructions.size() == 0) return false;
        if (mapping.tailNoCancelArgSlots > 0 && mapping.tailNoCancelArgSlots > argSlots(method)) return false;
        ArrayList<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                returns.add(insn);
            }
        }
        if (returns.isEmpty()) return false;
        for (AbstractInsnNode returnInsn : returns) {
            InsnList patch = new InsnList();
            patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            if (mapping.tailNoCancelArgSlots > 0) {
                patch.add(new VarInsnNode(Opcodes.ALOAD, mapping.tailNoCancelArgSlots));
            }
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    mapping.tailNoCancelMethod, mapping.tailNoCancelDesc, false));
            method.instructions.insertBefore(returnInsn, patch);
        }
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

    /**
     * Definition hooks rewrite a vanilla body, so the readable name is matched only for vanilla
     * classes; call sites are matched against either name but only for a vanilla callee owner.
     */
    private static boolean hasTargetMethodReference(ClassNode classNode, boolean isMinecraftClass) {
        for (MethodNode method : classNode.methods) {
            if (TARGET_SRG_SIGS.contains(method.name + method.desc)) return true;
            if (isMinecraftClass && TARGET_SIGS.contains(method.name + method.desc)) return true;
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (!isVanillaOwner(mi.owner)) continue;
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

    private static boolean processSelfDefense(ClassNode classNode) {
        if (!hasSelfDefenseTargets(classNode)) return false;
        boolean modified = false;
        modified |= processModListDefense(classNode);
        modified |= processMixinConfigDefense(classNode);
        for (MethodNode method : classNode.methods) {
            boolean methodMod = false;
            methodMod |= processClassForNameDefense(method);
            methodMod |= processStackTraceDefense(method);
            methodMod |= processGetAllLoadedClassesDefense(method);
            if (methodMod) {
                method.maxStack += 4;
                transformedMethods.incrementAndGet();
                modified = true;
            }
        }
        return modified;
    }

    private static boolean hasSelfDefenseTargets(ClassNode classNode) {
        if ("net/minecraftforge/fml/ModList".equals(classNode.name)) return true;
        for (MethodNode method : classNode.methods) {
            if ("shouldApplyMixin".equals(method.name)
                    && "(Ljava/lang/String;Ljava/lang/String;)Z".equals(method.desc)) return true;
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) insn;
                if ("forName".equals(mi.name) && "java/lang/Class".equals(mi.owner)) return true;
                if ("getStackTrace".equals(mi.name) && ("java/lang/Thread".equals(mi.owner) || "java/lang/Throwable".equals(mi.owner))) return true;
                if ("getAllLoadedClasses".equals(mi.name) && mi.desc.equals("()[Ljava/lang/Class;")) return true;
            }
        }
        return false;
    }

    private static boolean processModListDefense(ClassNode classNode) {
        if (!"net/minecraftforge/fml/ModList".equals(classNode.name)) return false;
        boolean modified = false;
        for (MethodNode method : classNode.methods) {
            if ("isLoaded".equals(method.name) && "(Ljava/lang/String;)Z".equals(method.desc)) {
                LabelNode skipLabel = new LabelNode(new Label());
                InsnList patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                        "shouldHideModId", "(Ljava/lang/Object;)Z", false));
                patch.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
                patch.add(new InsnNode(Opcodes.ICONST_0));
                patch.add(new InsnNode(Opcodes.IRETURN));
                patch.add(skipLabel);
                method.instructions.insertBefore(method.instructions.getFirst(), patch);
                method.maxStack += 2;
                modified = true;
            }
            if ("getModContainerById".equals(method.name)
                    && "(Ljava/lang/String;)Ljava/util/Optional;".equals(method.desc)) {
                LabelNode skipLabel = new LabelNode(new Label());
                InsnList patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                        "shouldHideModId", "(Ljava/lang/Object;)Z", false));
                patch.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
                patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional",
                        "empty", "()Ljava/util/Optional;", false));
                patch.add(new InsnNode(Opcodes.ARETURN));
                patch.add(skipLabel);
                method.instructions.insertBefore(method.instructions.getFirst(), patch);
                method.maxStack += 2;
                modified = true;
            }
            if ("getMods".equals(method.name) && method.desc.endsWith(")Ljava/util/List;")) {
                ArrayList<AbstractInsnNode> returns = new ArrayList<>();
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.ARETURN) returns.add(insn);
                }
                for (AbstractInsnNode retInsn : returns) {
                    InsnList patch = new InsnList();
                    patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                            "filterModList", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                    patch.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
                    method.instructions.insertBefore(retInsn, patch);
                }
                if (!returns.isEmpty()) {
                    method.maxStack += 2;
                    modified = true;
                }
            }
        }
        return modified;
    }

    private static boolean processMixinConfigDefense(ClassNode classNode) {
        boolean modified = false;
        for (MethodNode method : classNode.methods) {
            if ("shouldApplyMixin".equals(method.name)
                    && "(Ljava/lang/String;Ljava/lang/String;)Z".equals(method.desc)) {
                if (method.instructions.size() == 0) continue;
                LabelNode skipLabel = new LabelNode(new Label());
                InsnList patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 2));
                patch.add(new LdcInsnNode("jp.mikumiku.lal."));
                patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                        "startsWith", "(Ljava/lang/String;)Z", false));
                patch.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
                patch.add(new InsnNode(Opcodes.ICONST_1));
                patch.add(new InsnNode(Opcodes.IRETURN));
                patch.add(skipLabel);
                method.instructions.insertBefore(method.instructions.getFirst(), patch);
                method.maxStack += 2;
                modified = true;
            }
        }
        return modified;
    }

    private static boolean processClassForNameDefense(MethodNode method) {
        if (method.instructions.size() == 0) return false;
        boolean modified = false;
        ArrayList<MethodInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode mi = (MethodInsnNode) insn;
            if (mi.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if (!"java/lang/Class".equals(mi.owner)) continue;
            if (!"forName".equals(mi.name)) continue;
            if (!"(Ljava/lang/String;)Ljava/lang/Class;".equals(mi.desc)) continue;
            targets.add(mi);
        }
        for (MethodInsnNode mi : targets) {
            InsnList patch = new InsnList();
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    "filterClassName", "(Ljava/lang/String;)Ljava/lang/String;", false));
            method.instructions.insertBefore(mi, patch);
            modified = true;
        }
        return modified;
    }

    private static boolean processStackTraceDefense(MethodNode method) {
        if (method.instructions.size() == 0) return false;
        boolean modified = false;
        ArrayList<MethodInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode mi = (MethodInsnNode) insn;
            if (mi.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            if (!"getStackTrace".equals(mi.name)) continue;
            if (!"()[Ljava/lang/StackTraceElement;".equals(mi.desc)) continue;
            if ("java/lang/Thread".equals(mi.owner) || "java/lang/Throwable".equals(mi.owner)) {
                targets.add(mi);
            }
        }
        for (MethodInsnNode mi : targets) {
            InsnList patch = new InsnList();
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    "filterLALFrames", "([Ljava/lang/StackTraceElement;)[Ljava/lang/StackTraceElement;", false));
            method.instructions.insert(mi, patch);
            modified = true;
        }
        return modified;
    }

    private static boolean processGetAllLoadedClassesDefense(MethodNode method) {
        if (method.instructions.size() == 0) return false;
        boolean modified = false;
        ArrayList<MethodInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode mi = (MethodInsnNode) insn;
            if (!"getAllLoadedClasses".equals(mi.name)) continue;
            if (!"()[Ljava/lang/Class;".equals(mi.desc)) continue;
            targets.add(mi);
        }
        for (MethodInsnNode mi : targets) {
            InsnList patch = new InsnList();
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    "filterLALClasses", "([Ljava/lang/Class;)[Ljava/lang/Class;", false));
            method.instructions.insert(mi, patch);
            modified = true;
        }
        return modified;
    }

    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String ENTITY_CLASS = "net/minecraft/world/entity/Entity";

    private static boolean injectServerLevelHelpers(ClassNode classNode) {
        boolean alreadyInjected = false;
        for (MethodNode m : classNode.methods) {
            if ("lal$safeGetEntity".equals(m.name)) { alreadyInjected = true; break; }
        }
        if (alreadyInjected) return false;

        String getEntityName = null;
        String getAllEntitiesName = null;
        String getEntityByUuidName = null;
        for (MethodNode m : classNode.methods) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (mi.getOpcode() != Opcodes.INVOKEVIRTUAL && mi.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
                if (getEntityName == null && "(I)Lnet/minecraft/world/entity/Entity;".equals(mi.desc)) {
                    getEntityName = mi.name;
                }
                if (getAllEntitiesName == null && "()Ljava/lang/Iterable;".equals(mi.desc)
                        && (mi.name.equals("getAllEntities") || mi.name.equals("m_8583_"))) {
                    getAllEntitiesName = mi.name;
                }
                if (getEntityByUuidName == null && "(Ljava/util/UUID;)Lnet/minecraft/world/entity/Entity;".equals(mi.desc)) {
                    getEntityByUuidName = mi.name;
                }
            }
        }
        if (getEntityName == null) {
            for (String name : new String[]{"m_6815_", "getEntity"}) {
                for (MethodNode m : classNode.methods) {
                    if (m.name.equals(name) && m.desc.startsWith("(I)")) {
                        getEntityName = name;
                        break;
                    }
                }
                if (getEntityName != null) break;
            }
        }
        if (getAllEntitiesName == null) {
            for (String name : new String[]{"m_8583_", "getAllEntities"}) {
                for (MethodNode m : classNode.methods) {
                    if (m.name.equals(name)) {
                        getAllEntitiesName = name;
                        break;
                    }
                }
                if (getAllEntitiesName != null) break;
            }
        }

        boolean modified = false;

        if (getEntityName != null) {
            MethodNode helper = new MethodNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "lal$safeGetEntity",
                    "(Lnet/minecraft/server/level/ServerLevel;I)Lnet/minecraft/world/entity/Entity;",
                    null, null
            );
            helper.instructions = new InsnList();
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL,
                    getEntityName, "(I)Lnet/minecraft/world/entity/Entity;", false));
            helper.instructions.add(new InsnNode(Opcodes.ARETURN));
            helper.maxStack = 2;
            helper.maxLocals = 2;
            classNode.methods.add(helper);
            modified = true;
        }

        if (getAllEntitiesName != null) {
            MethodNode helper = new MethodNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "lal$safeGetAllEntities",
                    "(Lnet/minecraft/server/level/ServerLevel;)Ljava/lang/Iterable;",
                    null, null
            );
            helper.instructions = new InsnList();
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL,
                    getAllEntitiesName, "()Ljava/lang/Iterable;", false));
            helper.instructions.add(new InsnNode(Opcodes.ARETURN));
            helper.maxStack = 1;
            helper.maxLocals = 1;
            classNode.methods.add(helper);
            modified = true;
        }

        if (getEntityByUuidName != null) {
            MethodNode helper = new MethodNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "lal$safeGetEntityByUuid",
                    "(Lnet/minecraft/server/level/ServerLevel;Ljava/util/UUID;)Lnet/minecraft/world/entity/Entity;",
                    null, null
            );
            helper.instructions = new InsnList();
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL,
                    getEntityByUuidName, "(Ljava/util/UUID;)Lnet/minecraft/world/entity/Entity;", false));
            helper.instructions.add(new InsnNode(Opcodes.ARETURN));
            helper.maxStack = 2;
            helper.maxLocals = 2;
            classNode.methods.add(helper);
            modified = true;
        }

        return modified;
    }
}
