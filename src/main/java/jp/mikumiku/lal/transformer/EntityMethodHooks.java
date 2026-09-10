package jp.mikumiku.lal.transformer;

import jp.mikumiku.lal.core.BreakRegistry;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.enforcement.BreakEnforcer;
import jp.mikumiku.lal.enforcement.EnforcementDaemon;
import jp.mikumiku.lal.enforcement.ImmortalEnforcer;
import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.enforcement.RegistryCleaner;
import jp.mikumiku.lal.item.LALBreakerItem;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.util.MixinUtil;
import jp.mikumiku.lal.core.KillSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class EntityMethodHooks {
    private static final Class<?> PART_ENTITY_CLASS;
    private static final java.lang.reflect.Method PART_ENTITY_GET_PARENT;
    static {
        try { jp.mikumiku.lal.util.NativeLoader.ensureLoaded(); } catch (Throwable ignored) {}
        Class<?> c = null;
        java.lang.reflect.Method gp = null;
        try {
            c = Class.forName("net.minecraftforge.entity.PartEntity");
            gp = c.getMethod("getParent");
        } catch (Throwable ignored) {}
        PART_ENTITY_CLASS = c;
        PART_ENTITY_GET_PARENT = gp;
    }
    public static boolean isPartEntity(Object obj) {
        return PART_ENTITY_CLASS != null && PART_ENTITY_CLASS.isInstance(obj);
    }
    public static Entity getPartEntityParent(Object partEntity) {
        if (PART_ENTITY_GET_PARENT == null || partEntity == null) return null;
        try {
            Object result = PART_ENTITY_GET_PARENT.invoke(partEntity);
            return result instanceof Entity ? (Entity) result : null;
        } catch (Throwable ignored) { return null; }
    }
    public static Entity[] getEntityParts(Entity entity) {
        try {
            java.lang.reflect.Method m = entity.getClass().getMethod("getParts");
            Object result = m.invoke(entity);
            if (result instanceof Entity[]) return (Entity[]) result;
            if (result instanceof Object[]) {
                Object[] arr = (Object[]) result;
                Entity[] out = new Entity[arr.length];
                for (int i = 0; i < arr.length; i++) {
                    if (arr[i] instanceof Entity) out[i] = (Entity) arr[i];
                }
                return out;
            }
        } catch (Throwable ignored) {}
        return null;
    }
    private static native void nativeSetBypass(boolean bypass);
    /**
     * The bundled native library is a Windows PE DLL. Where it is absent every native call builds a
     * stack-traced UnsatisfiedLinkError, and setBypass is on the hottest path.
     */
    private static final boolean NATIVE_AVAILABLE = jp.mikumiku.lal.util.NativeLoader.isLoaded();
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static final AtomicLong hookCallCount = new AtomicLong(0);
    private static final AtomicLong totalHookCalls = new AtomicLong(0);
    public static final ConcurrentHashMap<UUID, Boolean> baseTickFired = new ConcurrentHashMap<>();
    public static volatile boolean mixinTickRan = false;
    public static final ConcurrentHashMap<UUID, Long> COLLECTING_PLAYERS = new ConcurrentHashMap<>();

    public static void startCollecting(UUID playerUuid, long expiryTick) {
        COLLECTING_PLAYERS.put(playerUuid, expiryTick);
    }
    public static final ConcurrentHashMap<UUID, Integer> lastTickSeen = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<UUID, Boolean> forcedTickThisTick = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<UUID, Integer> normalTickSeen = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<UUID, Boolean> normalTickAttempted = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, java.lang.ref.WeakReference<Entity>> CONSTRUCTED_ENTITIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> CONSTRUCTED_ENTITY_NANO = new ConcurrentHashMap<>();
    public static volatile long clientLastTickedNano = 0;
    private static volatile int lastForcedTickRun = -1;

    public static void setBypass(boolean bypass) {
        BYPASS.set(bypass);
        if (NATIVE_AVAILABLE) {
            try { nativeSetBypass(bypass); } catch (Throwable t) {
                BYPASS.set(bypass);
            }
        }
    }

    public static boolean isBypass() {
        return BYPASS.get();
    }

    private static void recordHookCall() {
        hookCallCount.incrementAndGet();
        totalHookCalls.incrementAndGet();
    }

    public static long getAndResetHookCallCount() {
        return hookCallCount.getAndSet(0);
    }

    /**
     * Never reset, unlike hookCallCount: integrity checks use a stall in this value as the only
     * observable evidence that the hooks are gone.
     */
    public static long getTotalHookCalls() {
        return totalHookCalls.get();
    }

    public static boolean tryRunForcedTick(int currentTick) {
        if (lastForcedTickRun == currentTick) return false;
        lastForcedTickRun = currentTick;
        return true;
    }

    public static boolean isPlayerMultiCheck(Object obj) {
        if (obj == null) return false;
        if (obj instanceof Player) return true;
        try {
            if (obj instanceof Entity) {
                if (((Entity) obj).getType() == net.minecraft.world.entity.EntityType.PLAYER) return true;
            }
        } catch (Throwable ignored) {}
        try {
            String className = obj.getClass().getName();
            if (className.contains("Player") && obj instanceof LivingEntity) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean checkImmortal(Object obj) {
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try { return CombatRegistry.isInImmortalSet((Entity) obj); }
        catch (Exception e) { return false; }
    }

    private static boolean checkKillSet(Object obj) {
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try { return CombatRegistry.isInKillSet((Entity) obj); }
        catch (Exception e) { return false; }
    }

    private static volatile java.lang.reflect.Method localPlayerLALCheck;
    private static volatile boolean localPlayerLALCheckResolved;

    public static boolean checkLocalPlayerHasLAL() {
        try {
            if (!localPlayerLALCheckResolved) {
                try {
                    Class<?> clientHandler = Class.forName("jp.mikumiku.lal.client.LALClientHandler");
                    localPlayerLALCheck = clientHandler.getMethod("isLocalPlayerHoldingLAL");
                } catch (Throwable ignored) {}
                localPlayerLALCheckResolved = true;
            }
            if (localPlayerLALCheck != null) {
                return (boolean) localPlayerLALCheck.invoke(null);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void onBaseTick(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return;
        if (!(obj instanceof LivingEntity)) return;
        LivingEntity entity = (LivingEntity) obj;
        try {
            UUID uuid = entity.getUUID();
            baseTickFired.put(uuid, Boolean.TRUE);

            // Only players are read back from these maps, so other entities skip the entry
            // and the Integer boxing.
            if (entity instanceof Player) {
                try {
                    Level level = entity.level();
                    if (level != null && !level.isClientSide()) {
                        int tick = level.getServer() != null ? level.getServer().getTickCount() : 0;
                        if (tick > 0) {
                            lastTickSeen.put(uuid, tick);
                            if (!forcedTickThisTick.containsKey(uuid)) {
                                normalTickSeen.put(uuid, tick);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            try {
                EnforcementDaemon.ensureRunning();
            } catch (Throwable ignored) {}

            if (entity instanceof Player) {
                Player player = (Player) entity;
                boolean hasEquip = LALSwordItem.hasLALEquipment(player);
                if (hasEquip && !CombatRegistry.isInKillSet(uuid) && !CombatRegistry.isInImmortalSet(uuid)) {
                    CombatRegistry.addToImmortalSet(uuid);
                } else if (!hasEquip && CombatRegistry.isInImmortalSet(uuid)) {
                    CombatRegistry.lal$removeFromImmortalSetInternal(uuid);
                }
            }

            try {
                EnforcementDaemon.trackEntity(entity);
            } catch (Exception ignored) {}

            if (CombatRegistry.isInImmortalSet(uuid)) {
                try {
                    ImmortalEnforcer.setRawDeathTime(entity, 0);
                    ImmortalEnforcer.setRawDead(entity, false);
                    ImmortalEnforcer.setRawHurtTime(entity, 0);
                    entity.deathTime = 0;
                    entity.hurtTime = 0;
                    if (entity.getArrowCount() > 0) {
                        setBypass(true);
                        try { entity.setArrowCount(0); } finally { setBypass(false); }
                    }
                    if (entity.getPose() == Pose.DYING) {
                        entity.setPose(Pose.STANDING);
                    }
                    float max = MixinUtil.safeMaxHealth(entity);
                    ImmortalEnforcer.setRawHealth(entity, max);
                } catch (Exception ignored) {}
            }

            try {
                if (BreakRegistry.isBreaking(uuid)) {
                    BreakEnforcer.enforce(entity);
                }
            } catch (Throwable ignored) {}
        } catch (Exception ignored) {}
    }

    public static boolean shouldBlockLivingTick(Object obj) {
        return onLivingTickEntry(obj);
    }

    public static boolean onLivingTickEntry(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof LivingEntity)) return false;
        LivingEntity entity = (LivingEntity) obj;
        try {
            UUID uuid = entity.getUUID();

            if (forcedTickThisTick.containsKey(uuid)) {
                normalTickAttempted.put(uuid, Boolean.TRUE);
                return true;
            }

            if (baseTickFired.remove(uuid) == null) {
                executeBaseTickLogic(entity, uuid);
            }

            if (CombatRegistry.isDeadConfirmed(uuid)) {
                return true;
            }
            if (CombatRegistry.isInKillSet(uuid)) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void executeBaseTickLogic(LivingEntity entity, UUID uuid) {
        try {
            if (entity instanceof Player) {
                Player player = (Player) entity;
                boolean hasEquip = LALSwordItem.hasLALEquipment(player);
                if (hasEquip && !CombatRegistry.isInKillSet(uuid) && !CombatRegistry.isInImmortalSet(uuid)) {
                    CombatRegistry.addToImmortalSet(uuid);
                } else if (!hasEquip && CombatRegistry.isInImmortalSet(uuid)) {
                    CombatRegistry.lal$removeFromImmortalSetInternal(uuid);
                }
            }

            try {
                EnforcementDaemon.trackEntity(entity);
            } catch (Exception ignored) {}

            if (CombatRegistry.isInImmortalSet(uuid)) {
                try {
                    ImmortalEnforcer.setRawDeathTime(entity, 0);
                    ImmortalEnforcer.setRawDead(entity, false);
                    ImmortalEnforcer.setRawHurtTime(entity, 0);
                    entity.deathTime = 0;
                    entity.hurtTime = 0;
                    if (entity.getArrowCount() > 0) {
                        setBypass(true);
                        try { entity.setArrowCount(0); } finally { setBypass(false); }
                    }
                    if (entity.getPose() == Pose.DYING) {
                        entity.setPose(Pose.STANDING);
                    }
                    float max = MixinUtil.safeMaxHealth(entity);
                    ImmortalEnforcer.setRawHealth(entity, max);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    public static void onAttack(Object player, Object target) {
        recordHookCall();
        if (BYPASS.get()) return;
        if (!(player instanceof Player) || !(target instanceof Entity)) return;
        try {
            Player p = (Player) player;
            Entity targetEntity = (Entity) target;
            if (isPartEntity(targetEntity)) {
                Entity parent = getPartEntityParent(targetEntity);
                if (parent != null) {
                    targetEntity = parent;
                }
            }
            if (targetEntity instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) targetEntity;
                Level level = targetEntity.level();
                if (level instanceof ServerLevel) {
                    if (LALSwordItem.hasLALEquipment(p)) {
                        KillEnforcer.forceKill(living, (ServerLevel) level, (Entity) p);
                    }
                    if (LALBreakerItem.isHoldingBreaker(p)) {
                        LALBreakerItem.asmBreakAttack(living, (ServerLevel) level, p);
                    }
                }
            }
        } catch (Exception ignored) {}
    }



    public static boolean shouldBlockSetPose(Object obj, Object pose) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (pose == Pose.DYING && CombatRegistry.isInImmortalSet(entity)) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockSetRemoved(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            UUID uuid = jp.mikumiku.lal.util.FieldAccessUtil.getEntityUuidDirect(entity);
            if (uuid == null) uuid = entity.getUUID();
            if (CombatRegistry.isInImmortalSet(entity)) {
                return true;
            }

            if (CombatRegistry.isInKillSet(uuid)) {
                if (!(entity instanceof LivingEntity)) {
                    corruptRawEntityHealth(entity);
                }
                return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void corruptRawEntityHealth(Entity entity) {
        try {
            for (Class<?> clazz = entity.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                if (clazz.getName().startsWith("java.")) break;
                for (java.lang.reflect.Field f : jp.mikumiku.lal.util.FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        String name = f.getName().toLowerCase();
                        if (f.getType() == float.class) {
                            if (name.contains("health") || name.contains("hp") || name.equals("h")
                                    || name.contains("currenthealth") || name.contains("curhp")) {
                                f.setFloat(entity, 0.0f);
                            }
                        } else if (f.getType() == boolean.class) {
                            if (name.contains("dead") || name.contains("isdead")
                                    || name.contains("shoulddead") || name.contains("killed")) {
                                f.setBoolean(entity, true);
                            }
                        } else if (f.getType() == int.class) {
                            if (name.contains("deathtime") || name.contains("deathtick")
                                    || name.contains("deathcount")) {
                                f.setInt(entity, 20);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean shouldBlockKill(Object obj) {
        recordHookCall(); return checkImmortal(obj);
    }

    public static boolean shouldBlockDiscard(Object obj) {
        recordHookCall(); return checkImmortal(obj);
    }

    public static boolean shouldBlockRemove(Object obj) {
        recordHookCall(); return checkImmortal(obj);
    }

    public static boolean shouldBlockMove(Object obj) {
        recordHookCall(); return checkKillSet(obj);
    }

    public static boolean shouldBlockSetPosRaw(Object obj, double x, double y, double z) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            UUID uuid = entity.getUUID();
            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) return true;
            if (CombatRegistry.isInImmortalSet(entity)) {
                if (Double.isNaN(x) || Double.isInfinite(x)
                 || Double.isNaN(y) || Double.isInfinite(y)
                 || Double.isNaN(z) || Double.isInfinite(z)) {
                    return true;
                }
                try {
                    double halfSize = entity.level().getWorldBorder().getSize() / 2.0;
                    double cx = entity.level().getWorldBorder().getCenterX();
                    double cz = entity.level().getWorldBorder().getCenterZ();
                    if (Math.abs(x - cx) > halfSize + 100 || Math.abs(z - cz) > halfSize + 100 || y < -1000 || y > 1000) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockEntityTick(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (entity instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) entity;
                UUID uuid = entity.getUUID();
                if (CombatRegistry.isInKillSet(uuid) && living.deathTime >= 60) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockSetDeltaMovement(Object obj, Object vec) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (entity instanceof Player) return false;
            if (!CombatRegistry.isInImmortalSet(entity)) return false;
            if (vec instanceof Vec3) {
                Vec3 v = (Vec3) vec;
                if (v.x == 0 && v.y == 0 && v.z == 0) return true;
                if (v.horizontalDistance() > 3.0 || Math.abs(v.y) > 5.0) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockPush(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        if (obj instanceof Player) return false;
        return checkImmortal(obj);
    }

    public static boolean shouldBlockHurt(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (entity instanceof Player && !LALSwordItem.hasLALEquipment((Player) entity)) {
                CombatRegistry.removeFromImmortalSet(entity.getUUID());
            }
        } catch (Exception ignored) {}
        return checkImmortal(obj);
    }

    public static boolean shouldBlockDie(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (entity instanceof Player && !LALSwordItem.hasLALEquipment((Player)entity)) {
                CombatRegistry.removeFromImmortalSet(entity.getUUID());
            }
            if (CombatRegistry.isInImmortalSet(entity)) return true;
            if (entity instanceof Player) {
                Player player = (Player) entity;
                if (LALSwordItem.hasLALEquipment(player)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockSetHealth(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof LivingEntity)) return false;
        try {
            LivingEntity entity = (LivingEntity) obj;
            if (entity instanceof Player && !LALSwordItem.hasLALEquipment((Player) entity)) {
                CombatRegistry.removeFromImmortalSet(entity.getUUID());
            }
            if (CombatRegistry.isInImmortalSet((Entity) entity)) {
                return true;
            }
            if (CombatRegistry.isInKillSet((Entity) entity)) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockTickDeath(Object obj) {
        recordHookCall(); return checkImmortal(obj);
    }

    public static boolean shouldBlockActuallyHurt(Object obj) {
        recordHookCall(); return checkImmortal(obj);
    }

    public static boolean shouldBlockKnockback(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            return CombatRegistry.isInImmortalSet(entity) || CombatRegistry.isInKillSet(entity);
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockSetNoGravity(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInImmortalSet(entity)) return true;
            if (entity instanceof Player && LALSwordItem.hasLALEquipment((Player) entity)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldReplaceMethod(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        Entity entity = (Entity) obj;
        try {
            return CombatRegistry.isInKillSet(entity)
                    || CombatRegistry.isInImmortalSet(entity)
                    || CombatRegistry.isDeadConfirmed(entity.getUUID());
        } catch (Exception e) {
            return false;
        }
    }


    public static float replaceGetHealth(Object obj) {
        if (!(obj instanceof LivingEntity)) return 20.0f;
        LivingEntity entity = (LivingEntity) obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) return sanitizeHealth(forced, entity);
            if (CombatRegistry.isInImmortalSet((Entity) entity)) {
                return Math.max(entity.getMaxHealth(), 1.0f);
            }
            if (CombatRegistry.isInKillSet((Entity) entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return 0.0f;
            }
        } catch (Exception ignored) {}
        return entity.getMaxHealth();
    }


    public static boolean replaceIsDeadOrDying(Object obj) {
        if (!(obj instanceof Entity)) return false;
        Entity entity = (Entity) obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) return forced <= 0.0f;
            if (CombatRegistry.isInImmortalSet(entity)) return false;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) return true;
        } catch (Exception ignored) {}
        return false;
    }


    public static boolean replaceIsAlive(Object obj) {
        if (!(obj instanceof Entity)) return true;
        Entity entity = (Entity) obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) return forced > 0.0f;
            if (CombatRegistry.isInImmortalSet(entity)) return true;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) return false;
        } catch (Exception ignored) {}
        return true;
    }


    public static boolean replaceIsRemoved(Object obj) {
        if (!(obj instanceof Entity)) return false;
        Entity entity = (Entity) obj;
        try {
            if (CombatRegistry.isInImmortalSet(entity)) return false;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) return true;
        } catch (Exception ignored) {}
        try {
            return entity.getRemovalReason() != null;
        } catch (Exception ignored) {}
        return false;
    }


    public static Entity.RemovalReason replaceGetRemovalReason(Object obj) {
        if (!(obj instanceof Entity)) return null;
        Entity entity = (Entity) obj;
        try {
            if (CombatRegistry.isInImmortalSet(entity)) return null;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return Entity.RemovalReason.KILLED;
            }
        } catch (Exception ignored) {}
        return null;
    }


    public static boolean replaceCanBeCollidedWith(Object obj) {
        return false;
    }


    public static boolean replaceIsPickable(Object obj) {
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInKillSet(entity)) return false;
            if (entity.level().isClientSide()) return true;
        } catch (Throwable ignored) {}
        return false;
    }


    public static AABB replaceGetBoundingBox(Object obj) {
        return new AABB(0, 0, 0, 0, 0, 0);
    }


    public static boolean replaceShouldDropLoot(Object obj) {
        return true;
    }


    public static boolean replaceShouldDropExperience(Object obj) {
        return true;
    }


    public static boolean replaceHurt(Object obj) {
        return false;
    }


    public static boolean replaceRemoveAllEffects(Object obj) {
        return false;
    }


    public static boolean shouldBlockSetArrowCount(Object obj) {
        recordHookCall(); return checkImmortal(obj);
    }

    public static boolean shouldBlockCanBeCollidedWith(Object obj) {
        recordHookCall(); return checkKillSet(obj);
    }


    public static boolean shouldBlockIsPickable(Object obj) {
        recordHookCall();
        if (checkKillSet(obj)) return true;
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (entity.level().isClientSide()) {
                try {
                    if (checkLocalPlayerHasLAL()) return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }


    public static boolean shouldBlockGetBoundingBox(Object obj) {
        recordHookCall(); return checkKillSet(obj);
    }


    public static boolean shouldBlockShouldDropLoot(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            return CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID());
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockShouldDropExperience(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            return CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID());
        } catch (Exception ignored) {}
        return false;
    }


    public static boolean shouldBlockRemoveAllEffects(Object obj) {
        recordHookCall(); return checkImmortal(obj);
    }


    public static boolean shouldBlockAddFreshEntity(Object level, Object entity) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(entity instanceof Entity)) return false;
        try {
            Entity e = (Entity) entity;
            UUID uuid = e.getUUID();
            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
                return true;
            }
            if (matchesKillSignature(e)) {
                CombatRegistry.addToKillSet(uuid);
                return true;
            }
            if (isConstructionRateLimited(e)) {
                CombatRegistry.addToKillSet(uuid);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean shouldBlockHandlePlayerCombatKill(Object handler, Object packet) {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getDeclaredMethod("getInstance").invoke(null);
            if (mc == null) return false;
            Object player = null;
            for (java.lang.reflect.Field f : mcClass.getDeclaredFields()) {
                if ("player".equals(f.getName()) || "f_91084_".equals(f.getName())) {
                    f.setAccessible(true);
                    player = f.get(mc);
                    break;
                }
            }
            if (!(player instanceof Player)) return false;
            Player p = (Player) player;
            return LALSwordItem.hasLALEquipment(p) || CombatRegistry.isInImmortalSet(p.getUUID());
        } catch (Throwable ignored) {}
        return false;
    }


    public static float getHealth(Object obj, float original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof LivingEntity)) return original;
        LivingEntity entity = (LivingEntity) obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) return sanitizeHealth(forced, entity);
            if (CombatRegistry.isInImmortalSet((Entity) entity)) {
                float result = Math.max(entity.getMaxHealth(), 1.0f);
                if (Float.isNaN(result) || Float.isInfinite(result) || result <= 0.0f) result = 20.0f;
                return result;
            }
            if (CombatRegistry.isInKillSet((Entity) entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return 0.0f;
            }
        } catch (Exception ignored) {}
        if (Float.isNaN(original) || Float.isInfinite(original)) return 20.0f;
        return original;
    }

    public static float getHealth(float original, Object obj) {
        return getHealth(obj, original);
    }


    public static boolean isDeadOrDying(Object obj, boolean original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        Entity entity = (Entity) obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) return forced <= 0.0f;
            if (CombatRegistry.isInImmortalSet(entity)) return false;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) return true;
        } catch (Exception ignored) {}
        return original;
    }

    public static boolean isDeadOrDying(boolean original, Object obj) {
        return isDeadOrDying(obj, original);
    }


    public static boolean isAlive(Object obj, boolean original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        Entity entity = (Entity) obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) return forced > 0.0f;
            if (CombatRegistry.isInImmortalSet(entity)) return true;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) return false;
        } catch (Exception ignored) {}
        return original;
    }

    public static boolean isAlive(boolean original, Object obj) {
        return isAlive(obj, original);
    }

    public static boolean isRemoved(Object obj, boolean original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        Entity entity = (Entity) obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null && forced <= 0.0f) return true;
            if (CombatRegistry.isInImmortalSet(entity)) return false;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) return true;
        } catch (Exception ignored) {}
        return original;
    }

    public static boolean isRemoved(boolean original, Object obj) {
        return isRemoved(obj, original);
    }

    public static Entity.RemovalReason getRemovalReason(Object obj, Entity.RemovalReason original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        Entity entity = (Entity) obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null && forced <= 0.0f) return Entity.RemovalReason.KILLED;
            if (CombatRegistry.isInImmortalSet(entity)) return null;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return Entity.RemovalReason.KILLED;
            }
        } catch (Exception ignored) {}
        return original;
    }

    public static Entity.RemovalReason getRemovalReason(Entity.RemovalReason original, Object obj) {
        return getRemovalReason(obj, original);
    }

    public static boolean canBeCollidedWith(Object obj, boolean original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        try {
            if (CombatRegistry.isInKillSet((Entity) obj)) return false;
        } catch (Exception ignored) {}
        return original;
    }

    public static boolean canBeCollidedWith(boolean original, Object obj) {
        return canBeCollidedWith(obj, original);
    }

    public static boolean isPickable(Object obj, boolean original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInKillSet(entity)) return false;
            if (!original && entity.level().isClientSide()) {
                try {
                    if (checkLocalPlayerHasLAL()) return true;
                } catch (Throwable ignored) {}
            }
        } catch (Exception ignored) {}
        return original;
    }

    public static boolean isPickable(boolean original, Object obj) {
        return isPickable(obj, original);
    }

    public static AABB getBoundingBox(Object obj, AABB original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        try {
            if (CombatRegistry.isInKillSet((Entity) obj)) return new AABB(0, 0, 0, 0, 0, 0);
        } catch (Exception ignored) {}
        return original;
    }

    public static AABB getBoundingBox(AABB original, Object obj) {
        return getBoundingBox(obj, original);
    }

    public static boolean hurt(Object obj, boolean original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        try {
            if (CombatRegistry.isInImmortalSet((Entity) obj)) return false;
        } catch (Exception ignored) {}
        return original;
    }

    public static boolean hurt(boolean original, Object obj) {
        return hurt(obj, original);
    }

    public static boolean removeAllEffects(Object obj, boolean original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        try {
            if (CombatRegistry.isInImmortalSet((Entity) obj)) return false;
        } catch (Exception ignored) {}
        return original;
    }

    public static boolean removeAllEffects(boolean original, Object obj) {
        return removeAllEffects(obj, original);
    }


    public static boolean shouldDropLoot(Object obj, boolean original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) return true;
        } catch (Exception ignored) {}
        return original;
    }

    public static boolean shouldDropLoot(boolean original, Object obj) {
        return shouldDropLoot(obj, original);
    }

    public static boolean shouldDropExperience(Object obj, boolean original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) return true;
        } catch (Exception ignored) {}
        return original;
    }

    public static boolean shouldDropExperience(boolean original, Object obj) {
        return shouldDropExperience(obj, original);
    }


    public static boolean addFreshEntity(Object level, boolean original) {
        return original;
    }


    public static volatile boolean mixinServerTickTailRan = false;
    private static volatile int lastServerTickTailTick = -1;
    private static volatile boolean serverTickKillDataRestored = false;

    public static void onServerTick(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return;
        if (!(obj instanceof ServerLevel)) return;

        ServerLevel level = (ServerLevel) obj;
        int currentTick = level.getServer().getTickCount();

        try {
            jp.mikumiku.lal.entity.LALEntityManager.tickAll();
        } catch (Throwable ignored) {}

        boolean mixin = mixinTickRan;
        mixinTickRan = false;

        if (!mixin) {
            try {
                EnforcementDaemon.ensureRunning();
            } catch (Throwable ignored) {}

            try {
                KillEnforcer.restoreEventBusIfNeeded();
            } catch (Throwable ignored) {}

            if (currentTick < 5) {
                serverTickKillDataRestored = false;
            }
            if (!serverTickKillDataRestored) {
                serverTickKillDataRestored = true;
                try {
                    KillSavedData data = KillSavedData.get(level);
                    for (UUID uuid : data.getKilledUuids()) {
                        if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) continue;
                        CombatRegistry.addToKillSet(uuid);
                        CombatRegistry.setForcedHealth(uuid, 0.0f);
                        CombatRegistry.markDroppedLoot(uuid);
                    }
                } catch (Throwable ignored) {}
            }

            int repairsThisTick = 0;
            int maxRepairsPerTick = 20;

            for (UUID uuid : CombatRegistry.getImmortalSet()) {
                if (repairsThisTick >= maxRepairsPerTick) break;
                Entity entity = level.getEntity(uuid);
                if (!(entity instanceof LivingEntity)) continue;
                ImmortalEnforcer.enforceImmortality((LivingEntity) entity);
                repairsThisTick++;
            }

            for (UUID uuid : CombatRegistry.getKillSet()) {
                Entity entity = level.getEntity(uuid);
                if (!(entity instanceof LivingEntity)) continue;
                LivingEntity living = (LivingEntity) entity;
                KillEnforcer.enforceDeathState(living);
                try {
                    KillEnforcer.directWriteDataItem(living.getEntityData(), LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
                } catch (Throwable ignored) {}
                living.deathTime = Math.max(living.deathTime, 1);
                CombatRegistry.setForcedHealth(uuid, 0.0f);
            }

            for (ServerPlayer player : level.players()) {
                UUID playerUuid = player.getUUID();
                boolean hasEquipment = LALSwordItem.hasLALEquipment((Player) player);
                boolean isInKillSet = CombatRegistry.isInKillSet(playerUuid);
                if (hasEquipment && !isInKillSet) {
                    if (!CombatRegistry.isInImmortalSet(playerUuid)) {
                        CombatRegistry.addToImmortalSet(playerUuid);
                    }
                } else if (!hasEquipment && !isInKillSet && CombatRegistry.isInImmortalSet(playerUuid)) {
                    CombatRegistry.lal$removeFromImmortalSetInternal(playerUuid);
                    CombatRegistry.clearForcedHealth(playerUuid);
                }
            }

            for (UUID uuid : new java.util.ArrayList<>(CombatRegistry.getKillSet())) {
                if (repairsThisTick >= maxRepairsPerTick) break;
                Entity entity = level.getEntity(uuid);
                if (entity instanceof LivingEntity) {
                    LivingEntity living = (LivingEntity) entity;
                    Integer killStartTick = CombatRegistry.getKillStartTick(uuid);
                    int ticksInKillSet = killStartTick != null ? currentTick - killStartTick : 0;
                    if (KillEnforcer.verifyKill(living)) {
                        KillEnforcer.executeRemoval(living, level);
                        CombatRegistry.confirmDead(uuid);
                    } else if (ticksInKillSet >= 65) {
                        KillEnforcer.executeKill(living, level);
                        CombatRegistry.confirmDead(uuid);
                        repairsThisTick++;
                    } else {
                        KillEnforcer.enforceDeathState(living);
                        try {
                            KillEnforcer.directWriteDataItem(living.getEntityData(), LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
                        } catch (Throwable ignored) {}
                        living.deathTime = Math.max(living.deathTime, 1);
                        living.noPhysics = true;
                        repairsThisTick++;
                    }
                } else {
                    CombatRegistry.confirmDead(uuid);
                }
            }

            for (UUID uuid : new java.util.ArrayList<>(CombatRegistry.getDeadConfirmedSet())) {
                if (CombatRegistry.shouldDeferHardRemove(uuid, currentTick, CombatRegistry.HARD_REMOVE_DELAY_TICKS)) continue;
                Entity entity = level.getEntity(uuid);
                if (entity == null) continue;
                RegistryCleaner.deleteFromAllRegistries(entity, level);
                try {
                    entity.setBoundingBox(new AABB(0, 0, 0, 0, 0, 0));
                    entity.noPhysics = true;
                } catch (Throwable ignored) {}
            }

            try {
                BreakRegistry.cleanup(currentTick);
                for (UUID breakUuid : BreakRegistry.getBreakingUuids()) {
                    Entity breakEntity = level.getEntity(breakUuid);
                    if (breakEntity instanceof LivingEntity) {
                        BreakEnforcer.enforce((LivingEntity) breakEntity);
                    }
                }
            } catch (Throwable ignored) {}

            for (UUID uuid : CombatRegistry.getImmortalSet()) {
                if (repairsThisTick >= maxRepairsPerTick) break;
                Entity entity = level.getEntity(uuid);
                if (!(entity instanceof LivingEntity)) continue;
                ImmortalEnforcer.enforceImmortality((LivingEntity) entity);
                repairsThisTick++;
            }

        }

        COLLECTING_PLAYERS.entrySet().removeIf(e -> currentTick > e.getValue());
        if (!COLLECTING_PLAYERS.isEmpty()) {
            try {
                ArrayList<ItemEntity> itemsToPick = new ArrayList<>();
                ArrayList<net.minecraft.world.entity.ExperienceOrb> xpToPick = new ArrayList<>();
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof ItemEntity) itemsToPick.add((ItemEntity) entity);
                    else if (entity instanceof net.minecraft.world.entity.ExperienceOrb) xpToPick.add((net.minecraft.world.entity.ExperienceOrb) entity);
                }
                for (ItemEntity ie : itemsToPick) {
                    ItemStack stack = ie.getItem();
                    if (stack.isEmpty()) continue;
                    for (UUID collectorId : COLLECTING_PLAYERS.keySet()) {
                        ServerPlayer collector = level.getServer().getPlayerList().getPlayer(collectorId);
                        if (collector == null) continue;
                        if (collector.addItem(stack.copy())) {
                            ie.discard();
                            break;
                        }
                    }
                }
                for (net.minecraft.world.entity.ExperienceOrb orb : xpToPick) {
                    if (orb.isRemoved()) continue;
                    int value = orb.getValue();
                    if (value <= 0) continue;
                    for (UUID collectorId : COLLECTING_PLAYERS.keySet()) {
                        ServerPlayer collector = level.getServer().getPlayerList().getPlayer(collectorId);
                        if (collector == null) continue;
                        collector.giveExperiencePoints(value);
                        orb.discard();
                        break;
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (currentTick % 100 == 0) {
            try { cleanupConstructedEntities(); } catch (Throwable ignored) {}
        }

        if (tryRunForcedTick(currentTick)) {
            forcedTickThisTick.clear();
            for (ServerPlayer player : level.players()) {
                try {
                    if (!LALSwordItem.hasLALEquipment((Player) player)) continue;
                    UUID playerUuid = player.getUUID();
                    if (normalTickAttempted.remove(playerUuid) != null) {
                        normalTickSeen.put(playerUuid, currentTick - 1);
                        continue;
                    }
                    Integer lastNormal = normalTickSeen.get(playerUuid);
                    if (lastNormal != null && lastNormal < currentTick - 1) {
                        forcedTickThisTick.put(playerUuid, Boolean.TRUE);
                        BYPASS.set(true);
                        try {
                            player.tick();
                        } finally {
                            BYPASS.set(false);
                        }
                        lastTickSeen.put(playerUuid, currentTick);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    public static void onLivingTickTail(Object obj) {
        if (BYPASS.get()) return;
        if (!(obj instanceof LivingEntity)) return;
        LivingEntity entity = (LivingEntity) obj;
        try {
            boolean isProtected = CombatRegistry.isInImmortalSet((Entity) entity);
            if (!isProtected && entity instanceof Player) {
                Player player = (Player) entity;
                isProtected = LALSwordItem.hasLALEquipment(player) && !CombatRegistry.isInKillSet(entity.getUUID());
            }
            if (!isProtected) return;
            if (entity.deathTime > 0) entity.deathTime = 0;
            if (entity.hurtTime > 0) entity.hurtTime = 0;
            if (entity.getPose() == Pose.DYING) {
                entity.setPose(Pose.STANDING);
                entity.refreshDimensions();
            }
            entity.noPhysics = false;
            entity.setNoGravity(false);
            try {
                float dataHealth = KillEnforcer.readDataItemValue(entity.getEntityData(), LivingEntity.DATA_HEALTH_ID);
                if (dataHealth <= 0.0f) {
                    float max = MixinUtil.safeMaxHealth(entity);
                    KillEnforcer.directWriteDataItem(entity.getEntityData(), LivingEntity.DATA_HEALTH_ID, max);
                }
            } catch (Exception ignored) {}
            try {
                ImmortalEnforcer.setRawDead(entity, false);
                ImmortalEnforcer.setRawDeathTime(entity, 0);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        try { onServerPlayerTick(obj); } catch (Throwable ignored) {}
    }

    public static void onServerLevelTickTail(Object obj) {
        try {
            Class<?> cls = Class.forName("jp.mikumiku.lal.entity.LALEntityManager");
            cls.getMethod("tickAll").invoke(null);
        } catch (Throwable ignored) {}
        if (BYPASS.get()) return;
        if (!(obj instanceof ServerLevel)) return;
        if (mixinServerTickTailRan) {
            mixinServerTickTailRan = false;
            return;
        }
        ServerLevel level = (ServerLevel) obj;
        int currentTick;
        try {
            currentTick = level.getServer().getTickCount();
        } catch (Throwable ignored) { return; }
        if (lastServerTickTailTick == currentTick) return;
        lastServerTickTailTick = currentTick;

        int repairsThisTick = 0;
        int maxRepairsPerTick = 20;

        for (UUID uuid : new java.util.ArrayList<>(CombatRegistry.getKillSet())) {
            if (repairsThisTick >= maxRepairsPerTick) break;
            Entity entity = level.getEntity(uuid);
            if (entity instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) entity;
                Integer killStartTick = CombatRegistry.getKillStartTick(uuid);
                int ticksInKillSet = killStartTick != null ? currentTick - killStartTick : 0;
                if (KillEnforcer.verifyKill(living)) {
                    KillEnforcer.initiateKill(living, level);
                    KillEnforcer.executeRemoval(living, level);
                    CombatRegistry.confirmDead(uuid);
                    repairsThisTick++;
                } else if (ticksInKillSet >= 65) {
                    KillEnforcer.executeKill(living, level);
                    CombatRegistry.confirmDead(uuid);
                    repairsThisTick++;
                } else {
                    KillEnforcer.enforceDeathState(living);
                    try {
                        KillEnforcer.directWriteDataItem(living.getEntityData(), LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
                    } catch (Throwable ignored) {}
                    if (KillEnforcer.detectMethodForgery(living) && ticksInKillSet >= 5) {
                        KillEnforcer.executeKill(living, level);
                        CombatRegistry.confirmDead(uuid);
                        repairsThisTick++;
                    } else {
                        living.noPhysics = true;
                        repairsThisTick++;
                    }
                }
            } else if (entity == null) {
                CombatRegistry.confirmDead(uuid);
            }
        }

        for (UUID uuid : new java.util.ArrayList<>(CombatRegistry.getDeadConfirmedSet())) {
            if (CombatRegistry.shouldDeferHardRemove(uuid, currentTick, CombatRegistry.HARD_REMOVE_DELAY_TICKS)) continue;
            Entity entity = level.getEntity(uuid);
            if (entity == null) continue;
            RegistryCleaner.deleteFromAllRegistries(entity, level);
            try {
                entity.setBoundingBox(new AABB(0, 0, 0, 0, 0, 0));
                entity.noPhysics = true;
            } catch (Throwable ignored) {}
        }

        for (UUID uuid : CombatRegistry.getImmortalSet()) {
            if (repairsThisTick >= maxRepairsPerTick) break;
            Entity entity = level.getEntity(uuid);
            if (!(entity instanceof LivingEntity)) continue;
            ImmortalEnforcer.enforceImmortality((LivingEntity) entity);
            repairsThisTick++;
        }

        try {
            jp.mikumiku.lal.entity.LALEntityManager.tickAll();
        } catch (Throwable ignored) {}
    }

    public static void onGuardEntityTick(Object level, Object entity) {
        recordHookCall();
        if (!(entity instanceof LivingEntity)) return;
        LivingEntity living = (LivingEntity) entity;
        try {
            UUID uuid = living.getUUID();
            if (CombatRegistry.isInImmortalSet(uuid) ||
                    (living instanceof Player && LALSwordItem.hasLALEquipment((Player) living))) {
                Level lvl = living.level();
                if (lvl != null && !lvl.isClientSide()) {
                    int tick = lvl.getServer() != null ? lvl.getServer().getTickCount() : 0;
                    if (tick > 0) lastTickSeen.put(uuid, tick);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static float sanitizeHealth(float value, LivingEntity entity) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return Math.max(entity.getMaxHealth(), 20.0f);
        }
        return value;
    }

            /** Hiding removes entries and they stay removed, so a full sweep per lookup just repeats work. */
    private static final long HIDE_SWEEP_INTERVAL_MS = 500L;
    private static volatile long lastByIdSweepMs = 0L;
    private static volatile long lastByUuidSweepMs = 0L;

    private static volatile java.lang.reflect.Method byIdEntrySetMethod;
    private static volatile java.lang.reflect.Method entryGetIntKeyMethod;
    private static volatile java.lang.reflect.Method entryGetValueMethod;
    private static volatile java.lang.reflect.Method byIdRemoveMethod;
    private static volatile boolean byIdReflectionResolved = false;

            /** The level's entity index is not thread safe, so only the owning thread may mutate it. */
    private static boolean mayMutateEntityIndex() {
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            return server == null || server.isSameThread();
        } catch (Throwable t) {
            return true;
        }
    }

    private static void resolveByIdReflection(Object byId) {
        if (byIdReflectionResolved) return;
        synchronized (EntityMethodHooks.class) {
            if (byIdReflectionResolved) return;
            try {
                for (String name : new String[]{"int2ObjectEntrySet", "entrySet"}) {
                    try {
                        byIdEntrySetMethod = byId.getClass().getMethod(name);
                        break;
                    } catch (Throwable ignored) {}
                }
                if (byIdEntrySetMethod != null) {
                    Object entrySet = byIdEntrySetMethod.invoke(byId);
                    if (entrySet instanceof Iterable) {
                        java.util.Iterator<?> it = ((Iterable<?>) entrySet).iterator();
                        if (it.hasNext()) {
                            Object entry = it.next();
                            try { entryGetIntKeyMethod = entry.getClass().getMethod("getIntKey"); } catch (Throwable ignored) {}
                            try { entryGetValueMethod = entry.getClass().getMethod("getValue"); } catch (Throwable ignored) {}
                        }
                    }
                }
                try { byIdRemoveMethod = byId.getClass().getMethod("remove", int.class); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
            byIdReflectionResolved = true;
        }
    }

    @SuppressWarnings("unchecked")
    public static Object getFilteredById(Object lookup) {
        try {
            Object byId = getByIdField(lookup);
            if (byId == null) return null;
            if (CombatRegistry.getDeadConfirmedSet().isEmpty() && CombatRegistry.getKillSet().isEmpty()) {
                return byId;
            }
            long now = System.currentTimeMillis();
            if (now - lastByIdSweepMs < HIDE_SWEEP_INTERVAL_MS) {
                return byId;
            }
            if (!mayMutateEntityIndex()) {
                return byId;
            }
            lastByIdSweepMs = now;
            resolveByIdReflection(byId);

            java.util.List<Integer> keysToRemove = new java.util.ArrayList<>();
            try {
                Object entrySet = byIdEntrySetMethod != null ? byIdEntrySetMethod.invoke(byId) : null;
                if (entrySet instanceof Iterable) {
                    for (Object entry : (Iterable<?>) entrySet) {
                        try {
                            int key;
                            if (entryGetIntKeyMethod != null) {
                                key = (int) entryGetIntKeyMethod.invoke(entry);
                            } else {
                                key = (int) ((java.util.Map.Entry<?, ?>) entry).getKey();
                            }
                            Object val;
                            if (entryGetValueMethod != null) {
                                val = entryGetValueMethod.invoke(entry);
                            } else {
                                val = ((java.util.Map.Entry<?, ?>) entry).getValue();
                            }
                            if (val instanceof Entity) {
                                UUID uuid = ((Entity) val).getUUID();
                                if (CombatRegistry.isDeadConfirmed(uuid)
                                        || (CombatRegistry.isInKillSet(uuid) && val instanceof LivingEntity
                                            && ((LivingEntity) val).deathTime >= 60)) {
                                    keysToRemove.add(key);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            for (int key : keysToRemove) {
                try {
                    if (byIdRemoveMethod != null) byIdRemoveMethod.invoke(byId, key);
                } catch (Throwable ignored) {}
            }
            return byId;
        } catch (Throwable t) {
            return getByIdField(lookup);
        }
    }

    @SuppressWarnings("unchecked")
    public static Object getFilteredByUuid(Object lookup) {
        try {
            Object byUuid = getByUuidField(lookup);
            if (byUuid == null) return null;
            if (CombatRegistry.getDeadConfirmedSet().isEmpty() && CombatRegistry.getKillSet().isEmpty()) {
                return byUuid;
            }
            long now = System.currentTimeMillis();
            if (now - lastByUuidSweepMs < HIDE_SWEEP_INTERVAL_MS) {
                return byUuid;
            }
            if (!mayMutateEntityIndex()) {
                return byUuid;
            }
            lastByUuidSweepMs = now;

            if (byUuid instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) byUuid;
                java.util.List<Object> keysToRemove = new java.util.ArrayList<>();
                try {
                    for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() instanceof UUID) {
                            UUID uuid = (UUID) entry.getKey();
                            if (CombatRegistry.isDeadConfirmed(uuid)) {
                                keysToRemove.add(uuid);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                for (Object key : keysToRemove) {
                    try { map.remove(key); } catch (Throwable ignored) {}
                }
            }
            return byUuid;
        } catch (Throwable t) {
            return getByUuidField(lookup);
        }
    }

    public static boolean shouldBlockShouldBeSaved(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try { return CombatRegistry.isInImmortalSet((Entity) obj); }
        catch (Exception ignored) {}
        return false;
    }

    public static boolean replaceShouldBeSaved(Object obj) {
        return true;
    }

    public static boolean shouldBeSaved(Object obj, boolean original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        try { if (CombatRegistry.isInImmortalSet((Entity) obj)) return true; }
        catch (Exception ignored) {}
        return original;
    }

    public static boolean shouldBeSaved(boolean original, Object obj) {
        return shouldBeSaved(obj, original);
    }

    public static boolean shouldBlockGetMaxHealth(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            return CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID());
        } catch (Exception e) { return false; }
    }

    public static float replaceGetMaxHealth(Object obj) {
        return 20.0f;
    }

    public static float getMaxHealth(Object obj, float original) {
        if (BYPASS.get()) return original;
        if (!(obj instanceof Entity)) return original;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return 20.0f;
            }
            if (CombatRegistry.isInImmortalSet(entity)) {
                return Math.max(original, 20.0f);
            }
        } catch (Exception ignored) {}
        return original;
    }

    public static float getMaxHealth(float original, Object obj) {
        return getMaxHealth(obj, original);
    }

    public static boolean shouldBlockSetLevelCallback(Object obj, Object callback) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (!CombatRegistry.isInImmortalSet(entity)) return false;
            if (callback == null) return true;
            if (callback == EntityInLevelCallback.NULL) return true;
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockItemStackHurt(Object obj) {
        return false;
    }

    public static boolean replaceItemStackHurt(Object obj) {
        return false;
    }

    public static boolean shouldBlockMobAi(Object obj) {
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity e = (Entity) obj;
            return CombatRegistry.isInKillSet(e) || CombatRegistry.isDeadConfirmed(e.getUUID());
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean replaceIsEffectiveAiFalse(Object obj) {
        return false;
    }

    private static volatile java.lang.reflect.Field byIdFieldCache;
    private static volatile java.lang.reflect.Field byUuidFieldCache;
    private static volatile boolean fieldsResolved = false;

    private static Object getByIdField(Object lookup) {
        try {
            resolveFields(lookup);
            if (byIdFieldCache != null) return byIdFieldCache.get(lookup);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object getByUuidField(Object lookup) {
        try {
            resolveFields(lookup);
            if (byUuidFieldCache != null) return byUuidFieldCache.get(lookup);
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean shouldBlockAddEntityUuid(Object entityManager, Object entityAccess) {
        if (BYPASS.get()) return false;
        if (!(entityAccess instanceof Entity)) return false;
        try {
            UUID u = ((Entity) entityAccess).getUUID();
            if (CombatRegistry.isInKillSet(u) || CombatRegistry.isDeadConfirmed(u)) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean shouldBlockStopTracking(Object manager, Object entity) {
        if (BYPASS.get()) return false;
        if (!(entity instanceof Entity)) return false;
        try { return CombatRegistry.isInImmortalSet((Entity) entity); }
        catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockEntitySectionRemove(Object section, Object entity) {
        if (BYPASS.get()) return false;
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        if (e instanceof Player) return false;
        try { return CombatRegistry.isInImmortalSet(e); }
        catch (Exception ignored) {}
        return false;
    }

    public static boolean shouldBlockEntityTickListRemove(Object tickList, Object entity) {
        if (BYPASS.get()) return false;
        if (!(entity instanceof Entity)) return false;
        try { return CombatRegistry.isInImmortalSet((Entity) entity); }
        catch (Exception ignored) {}
        return false;
    }

    private static volatile java.lang.reflect.Field callbackEntityField;
    private static volatile boolean callbackEntityFieldResolved;

    public static boolean shouldBlockCallbackOnRemove(Object callback, Object removalReason) {
        recordHookCall();
        if (isBypass()) return false;
        try {
            if (!callbackEntityFieldResolved) {
                synchronized (EntityMethodHooks.class) {
                    if (!callbackEntityFieldResolved) {
                        for (java.lang.reflect.Field f : callback.getClass().getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            Class<?> ft = f.getType();
                            if (Entity.class.isAssignableFrom(ft) || ft.getName().contains("EntityAccess")) {
                                f.setAccessible(true);
                                callbackEntityField = f;
                                break;
                            }
                        }
                        callbackEntityFieldResolved = true;
                    }
                }
            }
            java.lang.reflect.Field f = callbackEntityField;
            if (f == null) return false;
            Object entityObj = f.get(callback);
            if (!(entityObj instanceof Entity e)) return false;
            UUID uuid = e.getUUID();
            if (CombatRegistry.isInImmortalSet(uuid)) return true;
            if (e instanceof Player player) {
                if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static volatile java.lang.reflect.Field synchedDataEntityField;
    private static volatile boolean synchedDataEntityFieldResolved;

    private static Entity getSynchedDataEntity(Object synchedData) {
        try {
            if (!synchedDataEntityFieldResolved) {
                synchedDataEntityFieldResolved = true;
                for (Class<?> cls = synchedData.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                    for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                        if (Entity.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            synchedDataEntityField = f;
                            break;
                        }
                    }
                    if (synchedDataEntityField != null) break;
                }
            }
            java.lang.reflect.Field f = synchedDataEntityField;
            if (f != null) return (Entity) f.get(synchedData);
        } catch (Throwable ignored) {}
        return null;
    }

    private static final java.util.Map<Object, java.lang.ref.WeakReference<Entity>> DATA_ITEM_OWNERS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void registerDataItemOwner(Object dataItem, Entity entity) {
        if (dataItem != null && entity != null) {
            DATA_ITEM_OWNERS.put(dataItem, new java.lang.ref.WeakReference<>(entity));
        }
    }

    public static Entity getDataItemOwner(Object dataItem) {
        if (dataItem == null) return null;
        java.lang.ref.WeakReference<Entity> ref = DATA_ITEM_OWNERS.get(dataItem);
        return ref != null ? ref.get() : null;
    }

    private static volatile java.lang.reflect.Field dataItemAccessorField;
    private static volatile java.lang.reflect.Field dataItemValueField;
    private static volatile boolean dataItemFieldsResolved;

    private static void resolveDataItemFields(Object dataItem) {
        if (dataItemFieldsResolved) return;
        synchronized (EntityMethodHooks.class) {
            if (dataItemFieldsResolved) return;
            try {
                for (java.lang.reflect.Field f : dataItem.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    String name = f.getName();
                    if (EntityDataAccessor.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        dataItemAccessorField = f;
                    } else if (name.equals("f_135391_") || name.equals("value")) {
                        f.setAccessible(true);
                        dataItemValueField = f;
                    }
                }
                if (dataItemValueField == null) {
                    for (java.lang.reflect.Field f : dataItem.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        if (EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                        if (f.getType() == boolean.class) continue;
                        f.setAccessible(true);
                        dataItemValueField = f;
                        break;
                    }
                }
            } catch (Throwable ignored) {}
            dataItemFieldsResolved = true;
        }
    }

    private static volatile int cachedHealthDataId = -1;

    private static int getHealthDataId() {
        if (cachedHealthDataId < 0) {
            try {
                cachedHealthDataId = LivingEntity.DATA_HEALTH_ID.getId();
            } catch (Throwable t) {
                cachedHealthDataId = 9;
            }
        }
        return cachedHealthDataId;
    }

    public static boolean shouldBlockDataItemSetValue(Object dataItem, Object newValue) {
        recordHookCall();
        if (isBypass()) return false;
        try {
            resolveDataItemFields(dataItem);
            java.lang.reflect.Field accF = dataItemAccessorField;
            if (accF == null) return false;
            EntityDataAccessor<?> acc = (EntityDataAccessor<?>) accF.get(dataItem);
            if (acc == null || acc.getId() != getHealthDataId()) return false;

            Entity entity = getDataItemOwner(dataItem);
            if (entity == null || !(entity instanceof LivingEntity)) return false;
            UUID uuid = entity.getUUID();

            if (CombatRegistry.isInImmortalSet(uuid)) {
                if (newValue instanceof Float f) {
                    java.lang.reflect.Field valF = dataItemValueField;
                    if (valF != null) {
                        Object current = valF.get(dataItem);
                        if (current instanceof Float curF && f < curF) return true;
                    }
                }
            }
            if (entity instanceof Player player) {
                if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) {
                    if (newValue instanceof Float f) {
                        java.lang.reflect.Field valF = dataItemValueField;
                        if (valF != null) {
                            Object current = valF.get(dataItem);
                            if (current instanceof Float curF && f < curF) return true;
                        }
                    }
                }
            }
            if (newValue instanceof Float f && f > 0.0f) {
                if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean shouldBlockDataItemSetDirty(Object dataItem, boolean dirtyFlag) {
        recordHookCall();
        if (isBypass()) return false;
        if (!dirtyFlag) return false;
        try {
            resolveDataItemFields(dataItem);
            java.lang.reflect.Field accF = dataItemAccessorField;
            if (accF == null) return false;
            EntityDataAccessor<?> acc = (EntityDataAccessor<?>) accF.get(dataItem);
            if (acc == null || acc.getId() != getHealthDataId()) return false;

            Entity entity = getDataItemOwner(dataItem);
            if (entity == null || !(entity instanceof LivingEntity living)) return false;
            UUID uuid = entity.getUUID();

            java.lang.reflect.Field valF = dataItemValueField;
            if (valF == null) return false;
            Object currentVal = valF.get(dataItem);

            if (CombatRegistry.isInImmortalSet(uuid)) {
                if (currentVal instanceof Float f) {
                    float max = MixinUtil.safeMaxHealth(living);
                    if (f < max) return true;
                }
            }
            if (entity instanceof Player player) {
                if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) {
                    if (currentVal instanceof Float f) {
                        float max = MixinUtil.safeMaxHealth(living);
                        if (f < max) return true;
                    }
                }
            }
            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
                if (currentVal instanceof Float f && f > 0.0f) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static volatile java.lang.reflect.Field itemsByIdField;
    private static volatile boolean itemsByIdFieldResolved;

    public static void registerAllDataItemOwners(Object synchedEntityData, Entity entity) {
        try {
            if (!itemsByIdFieldResolved) {
                synchronized (EntityMethodHooks.class) {
                    if (!itemsByIdFieldResolved) {
                        for (java.lang.reflect.Field f : net.minecraft.network.syncher.SynchedEntityData.class.getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            Class<?> ft = f.getType();
                            if (ft.isArray() || ft.getName().contains("Int2Object")) {
                                f.setAccessible(true);
                                itemsByIdField = f;
                                break;
                            }
                        }
                        itemsByIdFieldResolved = true;
                    }
                }
            }
            java.lang.reflect.Field ibf = itemsByIdField;
            if (ibf == null) return;
            Object items = ibf.get(synchedEntityData);
            if (items == null) return;
            if (items.getClass().isArray()) {
                Object[] arr = (Object[]) items;
                for (Object item : arr) {
                    if (item != null) registerDataItemOwner(item, entity);
                }
            } else {
                try {
                    java.lang.reflect.Method valuesMethod = items.getClass().getMethod("values");
                    Object values = valuesMethod.invoke(items);
                    if (values instanceof Iterable<?> iter) {
                        for (Object item : iter) {
                            if (item != null) registerDataItemOwner(item, entity);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    public static boolean shouldBlockSynchedDataSet(Object synchedData, Object accessor, Object value) {
        if (BYPASS.get()) return false;
        try {
            Entity entity = getSynchedDataEntity(synchedData);
            if (entity == null) return false;
            if (entity.level() == null) return false;
            UUID uuid = entity.getUUID();
            if (value instanceof Float && entity instanceof LivingEntity) {
                float f = (Float) value;
                EntityDataAccessor<?> acc;
                try { acc = (EntityDataAccessor<?>) accessor; } catch (Exception e) { return false; }
                try { if (acc.getId() != LivingEntity.DATA_HEALTH_ID.getId()) return false; } catch (Exception e) { return false; }
                if (CombatRegistry.isInImmortalSet(uuid)) {
                    LivingEntity living = (LivingEntity) entity;
                    float max = MixinUtil.safeMaxHealth(living);
                    if (f < max) return true;
                }
                if (entity instanceof Player) {
                    Player player = (Player) entity;
                    if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) {
                        float max = MixinUtil.safeMaxHealth((LivingEntity) entity);
                        if (f < max) return true;
                    }
                }
                if (f > 0 && (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid))) return true;
                return false;
            }
            if (value instanceof Pose && value == Pose.DYING) {
                if (CombatRegistry.isInImmortalSet(uuid)) return true;
                if (entity instanceof Player) {
                    Player player = (Player) entity;
                    if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) return true;
                }
            }
            if (value instanceof Boolean && (Boolean) value && entity instanceof LivingEntity) {
                EntityDataAccessor<?> acc;
                try { acc = (EntityDataAccessor<?>) accessor; } catch (Exception e) { return false; }
                int id = acc.getId();
                boolean isVanillaBoolean = (id == 3 || id == 4 || id == 5 || id == 10);
                if (!isVanillaBoolean) {
                    if (CombatRegistry.isInImmortalSet(uuid)) return true;
                    if (entity instanceof Player) {
                        Player player = (Player) entity;
                        if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) return true;
                    }
                }
            }
            if (value instanceof Integer && entity instanceof LivingEntity) {
                int intVal = (Integer) value;
                boolean isProtectedPlayer = entity instanceof Player
                        && !CombatRegistry.isInKillSet(uuid)
                        && LALSwordItem.hasLALEquipment((Player) entity);
                if ((CombatRegistry.isInImmortalSet(uuid) || isProtectedPlayer) && intVal <= 0) {
                    EntityDataAccessor<?> acc;
                    try { acc = (EntityDataAccessor<?>) accessor; } catch (Exception e) { return false; }
                    int id = acc.getId();
                    if (id > 15) {
                        try {
                            Object cur = entity.getEntityData().get((EntityDataAccessor) accessor);
                            if (cur instanceof Integer && (Integer) cur > 0) return true;
                        } catch (Exception ignored2) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static void onRenderLevelTail(Object levelRenderer) {
        try {
            Class<?> cls = Class.forName("jp.mikumiku.lal.entity.LALEntityRenderer");
            cls.getMethod("renderAllFallback").invoke(null);
        } catch (Throwable ignored) {}
    }

    public static void onGameRendererRenderLevelTail(Object gameRenderer) {
        try {
            Class<?> cls = Class.forName("jp.mikumiku.lal.entity.LALEntityRenderer");
            cls.getMethod("renderAllFallback").invoke(null);
        } catch (Throwable ignored) {}
    }

    public static void onEntityConstructed(Object obj) {
        if (BYPASS.get()) return;
        if (!(obj instanceof Entity)) return;
        Entity entity = (Entity) obj;
        try {
            String className = entity.getClass().getName();
            if (className.startsWith("jp.mikumiku.lal")) return;
            if (entity instanceof Player) return;
            UUID uuid = entity.getUUID();
            if (uuid == null) return;
            CONSTRUCTED_ENTITIES.put(uuid, new java.lang.ref.WeakReference<>(entity));
            CONSTRUCTED_ENTITY_NANO.put(uuid, System.nanoTime());
        } catch (Throwable ignored) {}
    }

    public static ConcurrentHashMap<UUID, java.lang.ref.WeakReference<Entity>> getConstructedEntities() {
        return CONSTRUCTED_ENTITIES;
    }

    public static ConcurrentHashMap<UUID, Long> getConstructedEntityTimes() {
        return CONSTRUCTED_ENTITY_NANO;
    }

    public static void cleanupConstructedEntities() {
        try {
            CONSTRUCTED_ENTITIES.entrySet().removeIf(entry -> {
                java.lang.ref.WeakReference<Entity> ref = entry.getValue();
                if (ref == null) return true;
                Entity entity = ref.get();
                if (entity == null) return true;
                if (CombatRegistry.isDeadConfirmed(entry.getKey())) return true;
                return false;
            });
            CONSTRUCTED_ENTITY_NANO.keySet().removeIf(uuid -> !CONSTRUCTED_ENTITIES.containsKey(uuid));
        } catch (Throwable ignored) {}
    }

    public static boolean shouldBlockRevive(Object entity) {
        if (entity == null) return false;
        try {
            if (entity instanceof Entity e) {
                UUID uuid = e.getUUID();
                if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean shouldBlockReviveCaps(Object entity) {
        return shouldBlockRevive(entity);
    }

    public static boolean shouldBlockCheckDespawn(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInImmortalSet(entity)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean shouldBlockRemoveWhenFarAway(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInImmortalSet(entity)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean replaceRemoveWhenFarAway(Object obj) {
        return false;
    }

    public static boolean shouldBlockShouldDespawnInPeaceful(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInImmortalSet(entity)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean replaceShouldDespawnInPeaceful(Object obj) {
        return false;
    }

    public static boolean shouldBlockIsPersistenceRequired(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInImmortalSet(entity)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean replaceIsPersistenceRequired(Object obj) {
        return true;
    }

            /** Weak, because nothing reads this and strong references pinned every attempted kill. */
    private static final ConcurrentHashMap<UUID, java.lang.ref.WeakReference<Entity>> STRONG_TRACKED =
            new ConcurrentHashMap<>();

    public static void addToStrongTracked(UUID uuid, Entity entity) {
        if (uuid != null && entity != null) STRONG_TRACKED.put(uuid, new java.lang.ref.WeakReference<>(entity));
    }

    public static void removeFromStrongTracked(UUID uuid) {
        if (uuid != null) STRONG_TRACKED.remove(uuid);
    }

    public static ConcurrentHashMap<UUID, java.lang.ref.WeakReference<Entity>> getStrongTracked() {
        return STRONG_TRACKED;
    }

    public static void processStrongTracked() {
        for (java.util.Map.Entry<UUID, java.lang.ref.WeakReference<Entity>> entry : STRONG_TRACKED.entrySet()) {
            UUID uuid = entry.getKey();
            java.lang.ref.WeakReference<Entity> ref = entry.getValue();
            Entity entity = ref != null ? ref.get() : null;
            if (entity == null) {
                STRONG_TRACKED.remove(uuid);
                continue;
            }
            try {
                if (CombatRegistry.isDeadConfirmed(uuid)) {
                    STRONG_TRACKED.remove(uuid);
                    continue;
                }
                if (CombatRegistry.isInKillSet(uuid)) {
                    if (entity instanceof LivingEntity living) {
                        jp.mikumiku.lal.enforcement.KillEnforcer.enforceDeathState(living);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }


    private static void resolveFields(Object lookup) {
        if (fieldsResolved) return;
        fieldsResolved = true;
        try {
            Class<?> clazz = lookup.getClass();
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    String name = f.getName();
                    if (name.equals("byId") || name.equals("f_156816_") || name.equals("f_156807_")) {
                        if (f.getType().getName().contains("Int2Object")) {
                            byIdFieldCache = f;
                        }
                    } else if (name.equals("byUuid") || name.equals("f_156817_") || name.equals("f_156808_")) {
                        if (java.util.Map.class.isAssignableFrom(f.getType())) {
                            byUuidFieldCache = f;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static final String LAL_PKG = "jp.mikumiku.lal";
    private static final String LAL_MOD_ID = "lal";

    private static boolean lal$isCallerExternal() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(stack.length, 20); i++) {
                String cn = stack[i].getClassName();
                if (cn.startsWith(LAL_PKG)) return false;
                if (cn.startsWith("java.") || cn.startsWith("sun.")
                        || cn.startsWith("jdk.") || cn.startsWith("com.sun.")) continue;
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static String filterClassName(String name) {
        if (name == null) return name;
        if (BYPASS.get()) return name;
        try {
            if (name.startsWith(LAL_PKG) && lal$isCallerExternal()) {
                return "java.lang.Object";
            }
        } catch (Throwable ignored) {}
        return name;
    }

    public static StackTraceElement[] filterLALFrames(StackTraceElement[] frames) {
        if (frames == null) return frames;
        if (BYPASS.get()) return frames;
        try {
            if (!lal$isCallerExternal()) return frames;
            int count = 0;
            for (StackTraceElement f : frames) {
                if (!f.getClassName().startsWith(LAL_PKG)) count++;
            }
            if (count == frames.length) return frames;
            StackTraceElement[] filtered = new StackTraceElement[count];
            int idx = 0;
            for (StackTraceElement f : frames) {
                if (!f.getClassName().startsWith(LAL_PKG)) filtered[idx++] = f;
            }
            return filtered;
        } catch (Throwable ignored) {}
        return frames;
    }

    public static boolean shouldHideModId(Object modId) {
        if (BYPASS.get()) return false;
        try {
            if (!(modId instanceof String s)) return false;
            if (!LAL_MOD_ID.equals(s)) return false;
            return lal$isCallerExternal();
        } catch (Throwable ignored) {}
        return false;
    }

    @SuppressWarnings("unchecked")
    public static Object filterModList(Object list) {
        if (BYPASS.get()) return list;
        try {
            if (!lal$isCallerExternal()) return list;
            if (!(list instanceof java.util.List<?> l)) return list;
            java.util.List<Object> filtered = new ArrayList<>();
            for (Object obj : l) {
                try {
                    java.lang.reflect.Method getModId = obj.getClass().getMethod("getModId");
                    String modId = (String) getModId.invoke(obj);
                    if (LAL_MOD_ID.equals(modId)) continue;
                } catch (Throwable ignored) {}
                filtered.add(obj);
            }
            return filtered;
        } catch (Throwable ignored) {}
        return list;
    }

    public static Class<?>[] filterLALClasses(Class<?>[] classes) {
        if (classes == null) return classes;
        if (BYPASS.get()) return classes;
        try {
            if (!lal$isCallerExternal()) return classes;
            int count = 0;
            for (Class<?> c : classes) {
                try {
                    if (!c.getName().startsWith(LAL_PKG)) count++;
                } catch (Throwable ignored) { count++; }
            }
            if (count == classes.length) return classes;
            Class<?>[] filtered = new Class<?>[count];
            int idx = 0;
            for (Class<?> c : classes) {
                try {
                    if (!c.getName().startsWith(LAL_PKG)) filtered[idx++] = c;
                } catch (Throwable ignored) {
                    if (idx < filtered.length) filtered[idx++] = c;
                }
            }
            return filtered;
        } catch (Throwable ignored) {}
        return classes;
    }

    private static final ConcurrentHashMap<String, Long> KILL_SIGNATURES = new ConcurrentHashMap<>();
    private static final long KILL_SIGNATURE_EXPIRY_NS = 2_000_000_000L;
    private static final Set<String> KILLED_ENTITY_CLASSES = ConcurrentHashMap.newKeySet();

    public static void recordKillSignature(Entity entity) {
        try {
            String className = entity.getClass().getName();
            if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) return;
            KILLED_ENTITY_CLASSES.add(className);
            String key = className + ":"
                    + Math.round(entity.getX()) + ":" + Math.round(entity.getY()) + ":" + Math.round(entity.getZ());
            KILL_SIGNATURES.put(key, System.nanoTime());
        } catch (Throwable ignored) {}
    }

    public static boolean matchesKillSignature(Entity entity) {
        try {
            String className = entity.getClass().getName();
            if (!KILLED_ENTITY_CLASSES.contains(className)) return false;
            String key = className + ":"
                    + Math.round(entity.getX()) + ":" + Math.round(entity.getY()) + ":" + Math.round(entity.getZ());
            Long timestamp = KILL_SIGNATURES.get(key);
            if (timestamp == null) return false;
            return System.nanoTime() - timestamp < KILL_SIGNATURE_EXPIRY_NS;
        } catch (Throwable ignored) {}
        return false;
    }

    public static void cleanupKillSignatures() {
        try {
            long now = System.nanoTime();
            KILL_SIGNATURES.entrySet().removeIf(e -> now - e.getValue() > KILL_SIGNATURE_EXPIRY_NS);
        } catch (Throwable ignored) {}
    }

    private static final ConcurrentHashMap<String, int[]> CONSTRUCTOR_FREQUENCY = new ConcurrentHashMap<>();
    private static volatile long lastFrequencyResetNano = System.nanoTime();
    private static final long FREQUENCY_WINDOW_NS = 2_000_000_000L;
    private static final int MAX_CONSTRUCTIONS_PER_WINDOW = 100;

    public static boolean isConstructionRateLimited(Entity entity) {
        try {
            String className = entity.getClass().getName();
            if (!KILLED_ENTITY_CLASSES.contains(className)) return false;
            long now = System.nanoTime();
            if (now - lastFrequencyResetNano > FREQUENCY_WINDOW_NS) {
                CONSTRUCTOR_FREQUENCY.clear();
                lastFrequencyResetNano = now;
            }
            int[] count = CONSTRUCTOR_FREQUENCY.computeIfAbsent(className, k -> new int[]{0});
            return ++count[0] > MAX_CONSTRUCTIONS_PER_WINDOW;
        } catch (Throwable ignored) {}
        return false;
    }

    private static volatile java.lang.reflect.Method safeGetEntityMethod;
    private static volatile java.lang.reflect.Method safeGetAllEntitiesMethod;
    private static volatile java.lang.reflect.Method safeGetEntityByUuidMethod;
    private static volatile boolean helperMethodsResolved = false;

    private static void resolveHelperMethods(Object level) {
        if (helperMethodsResolved) return;
        helperMethodsResolved = true;
        try {
            Class<?> slClass = level.getClass();
            try {
                safeGetEntityMethod = slClass.getMethod("lal$safeGetEntity",
                        slClass, int.class);
            } catch (Throwable ignored) {}
            try {
                safeGetAllEntitiesMethod = slClass.getMethod("lal$safeGetAllEntities",
                        slClass);
            } catch (Throwable ignored) {}
            try {
                safeGetEntityByUuidMethod = slClass.getMethod("lal$safeGetEntityByUuid",
                        slClass, UUID.class);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    public static Entity safeGetEntity(Object levelObj, int id) {
        if (!(levelObj instanceof net.minecraft.server.level.ServerLevel)) return null;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) levelObj;
        resolveHelperMethods(level);
        if (safeGetEntityMethod != null) {
            try {
                Object result = safeGetEntityMethod.invoke(null, level, id);
                if (result instanceof Entity) return (Entity) result;
            } catch (Throwable ignored) {}
        }
        try {
            Entity e = level.getEntity(id);
            if (e != null) return e;
        } catch (Throwable ignored) {}
        try {
            for (java.util.Map.Entry<UUID, java.lang.ref.WeakReference<Entity>> entry : CONSTRUCTED_ENTITIES.entrySet()) {
                java.lang.ref.WeakReference<Entity> ref = entry.getValue();
                if (ref == null) continue;
                Entity e = ref.get();
                if (e == null) continue;
                if (jp.mikumiku.lal.util.FieldAccessUtil.getEntityIdDirect(e) == id) return e;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Entity safeGetEntityByUuid(Object levelObj, UUID uuid) {
        if (uuid == null) return null;
        if (!(levelObj instanceof net.minecraft.server.level.ServerLevel)) return null;
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) levelObj;
        resolveHelperMethods(level);
        if (safeGetEntityByUuidMethod != null) {
            try {
                Object result = safeGetEntityByUuidMethod.invoke(null, level, uuid);
                if (result instanceof Entity) return (Entity) result;
            } catch (Throwable ignored) {}
        }
        try {
            Entity e = level.getEntity(uuid);
            if (e != null) return e;
        } catch (Throwable ignored) {}
        try {
            java.lang.ref.WeakReference<Entity> ref = CONSTRUCTED_ENTITIES.get(uuid);
            if (ref != null) {
                Entity e = ref.get();
                if (e != null) return e;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Iterable<Entity> safeGetAllEntities(Object levelObj) {
        if (!(levelObj instanceof net.minecraft.server.level.ServerLevel)) return java.util.Collections.emptyList();
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) levelObj;
        resolveHelperMethods(level);
        java.util.List<Entity> result = new java.util.ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        if (safeGetAllEntitiesMethod != null) {
            try {
                Object iter = safeGetAllEntitiesMethod.invoke(null, level);
                if (iter instanceof Iterable) {
                    for (Object obj : (Iterable<?>) iter) {
                        if (obj instanceof Entity e) {
                            seen.add(jp.mikumiku.lal.util.FieldAccessUtil.getEntityIdDirect(e));
                            result.add(e);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (result.isEmpty()) {
            try {
                for (Entity e : level.getAllEntities()) {
                    seen.add(jp.mikumiku.lal.util.FieldAccessUtil.getEntityIdDirect(e));
                    result.add(e);
                }
            } catch (Throwable ignored) {}
        }
        try {
            for (java.util.Map.Entry<UUID, java.lang.ref.WeakReference<Entity>> entry : CONSTRUCTED_ENTITIES.entrySet()) {
                java.lang.ref.WeakReference<Entity> ref = entry.getValue();
                if (ref == null) continue;
                Entity e = ref.get();
                if (e == null) continue;
                int eid = jp.mikumiku.lal.util.FieldAccessUtil.getEntityIdDirect(e);
                if (!seen.contains(eid)) {
                    result.add(e);
                }
            }
        } catch (Throwable ignored) {}
        return result;
    }

    public static void onServerStopping(Object server) {
        try {
            jp.mikumiku.lal.entity.LALEntityManager.onServerStoppingDirect();
        } catch (Throwable ignored) {}
    }

    public static void onPlayerJoined(Object playerListOrSelf, Object player) {
        if (!(player instanceof ServerPlayer sp)) return;
        try { jp.mikumiku.lal.network.LALNetwork.sendKeyToPlayer(sp); } catch (Throwable ignored) {}
        try { jp.mikumiku.lal.entity.LALEntityManager.onPlayerLoginDirect(sp); } catch (Throwable ignored) {}
    }

    public static void onPlayerDisconnect(Object playerOrListener) {
        ServerPlayer sp = null;
        if (playerOrListener instanceof ServerPlayer) {
            sp = (ServerPlayer) playerOrListener;
        } else {
            try {
                java.lang.reflect.Field f = null;
                for (String name : new String[]{"player", "f_9743_"}) {
                    try {
                        f = playerOrListener.getClass().getDeclaredField(name);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (f != null) {
                    f.setAccessible(true);
                    Object val = f.get(playerOrListener);
                    if (val instanceof ServerPlayer) sp = (ServerPlayer) val;
                }
            } catch (Throwable ignored) {}
        }
        if (sp == null) return;
        try { CombatRegistry.removeFromImmortalSet(sp.getUUID()); } catch (Throwable ignored) {}
        try { jp.mikumiku.lal.entity.LALEntityManager.onPlayerLogoutDirect(sp); } catch (Throwable ignored) {}
    }

    public static void onServerPlayerTick(Object player) {
        if (!(player instanceof ServerPlayer sp)) return;
        try { jp.mikumiku.lal.item.LALArmorItem.checkAndRemoveImmortality(sp); } catch (Throwable ignored) {}
    }

    public static void onEntityAddedToLevel(Object level, Object entity) {
        if (BYPASS.get()) return;
        if (!(entity instanceof Entity e)) return;
        if (!(level instanceof ServerLevel sl)) return;
        try {
            UUID uuid = e.getUUID();
            if (CombatRegistry.isDeadConfirmed(uuid) && entity instanceof LivingEntity living) {
                if (!(entity instanceof ServerPlayer)) {
                    CombatRegistry.addToKillSet(uuid);
                    jp.mikumiku.lal.enforcement.KillEnforcer.executeKill(living, sl);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void onArrowHitEntity(Object arrow, Object hitResult) {
        if (!(arrow instanceof net.minecraft.world.entity.projectile.AbstractArrow aa)) return;
        if (aa.getBaseDamage() < 2.0E9) return;
        if (!(hitResult instanceof net.minecraft.world.phys.EntityHitResult ehr)) return;
        Entity target = ehr.getEntity();
        if (!(target instanceof LivingEntity living)) return;
        try {
            setBypass(true);
            try {
                living.hurt(aa.damageSources().arrow(aa, aa.getOwner()), (float) aa.getBaseDamage());
            } finally {
                setBypass(false);
            }
        } catch (Throwable ignored) {}
    }

    public static boolean shouldBlockExit() {
        if (BYPASS.get()) return false;
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (StackTraceElement ste : stack) {
                if (ste.getClassName().startsWith("jp.mikumiku.lal.")) return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    public static boolean shouldBlockInteract(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            UUID uuid = entity.getUUID();
            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static net.minecraft.world.InteractionResult replaceInteractFail(Object obj) {
        return net.minecraft.world.InteractionResult.FAIL;
    }

    public static boolean shouldBlockHeal(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean shouldBlockAddEffect(Object obj, Object effect) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            UUID uuid = entity.getUUID();
            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) return true;
            if (CombatRegistry.isInImmortalSet(entity)) {
                if (effect instanceof net.minecraft.world.effect.MobEffectInstance mei) {
                    try {
                        if (mei.getEffect().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) return true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean replaceAddEffectFalse(Object obj) {
        return false;
    }

    public static boolean shouldBlockSetAbsorptionAmount(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            UUID uuid = entity.getUUID();
            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) return true;
            if (CombatRegistry.isInImmortalSet(entity)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean shouldBlockSetInvulnerable(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
            UUID uuid = entity.getUUID();
            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) return true;
            if (CombatRegistry.isInImmortalSet(entity)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

}
