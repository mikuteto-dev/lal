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
import net.minecraftforge.entity.PartEntity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class EntityMethodHooks {
    static {
        try { System.loadLibrary("lal"); } catch (Throwable ignored) {}
    }
    private static native void nativeSetBypass(boolean bypass);
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static final AtomicLong hookCallCount = new AtomicLong(0);
    public static final ConcurrentHashMap<UUID, Boolean> baseTickFired = new ConcurrentHashMap<>();
    public static volatile boolean mixinTickRan = false;
    public static final ConcurrentHashMap<UUID, Integer> lastTickSeen = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<UUID, Boolean> forcedTickThisTick = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<UUID, Integer> normalTickSeen = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<UUID, Boolean> normalTickAttempted = new ConcurrentHashMap<>();
    public static volatile long clientLastTickedNano = 0;
    private static volatile int lastForcedTickRun = -1;

    public static void setBypass(boolean bypass) {
        BYPASS.set(bypass);
        try { nativeSetBypass(bypass); } catch (Throwable ignored) {}
    }

    public static boolean isBypass() {
        return BYPASS.get();
    }

    private static void recordHookCall() {
        hookCallCount.incrementAndGet();
    }

    public static long getAndResetHookCallCount() {
        return hookCallCount.getAndSet(0);
    }

    public static boolean tryRunForcedTick(int currentTick) {
        if (lastForcedTickRun == currentTick) return false;
        lastForcedTickRun = currentTick;
        return true;
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

            try {
                EnforcementDaemon.ensureRunning();
            } catch (Throwable ignored) {}

            if (entity instanceof Player) {
                Player player = (Player) entity;
                boolean hasEquip = LALSwordItem.hasLALEquipment(player);
                if (hasEquip && !CombatRegistry.isInKillSet(uuid) && !CombatRegistry.isInImmortalSet(uuid)) {
                    CombatRegistry.addToImmortalSet(uuid);
                } else if (!hasEquip && CombatRegistry.isInImmortalSet(uuid)) {
                    CombatRegistry.removeFromImmortalSet(uuid);
                }
            }

            try {
                Class<?> daemonClass = Class.forName("jp.mikumiku.lal.enforcement.EnforcementDaemon");
                daemonClass.getMethod("trackEntity", LivingEntity.class).invoke(null, entity);
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
                    float max = entity.getMaxHealth();
                    if (max <= 0.0f) max = 20.0f;
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
                    CombatRegistry.removeFromImmortalSet(uuid);
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
                    float max = entity.getMaxHealth();
                    if (max <= 0.0f) max = 20.0f;
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
            if (targetEntity instanceof PartEntity) {
                Entity parent = ((PartEntity<?>)targetEntity).getParent();
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
            UUID uuid = entity.getUUID();
            if (CombatRegistry.isInImmortalSet(entity)) {
                return true;
            }

            if (CombatRegistry.isInKillSet(entity)) {
                return false;
            }
        } catch (Exception ignored) {}
        return false;
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

    public static boolean shouldBlockSetPosRaw(Object obj) {
        recordHookCall(); return checkKillSet(obj);
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
        recordHookCall(); return checkImmortal(obj);
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
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) return true;
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
                    living.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
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
                    CombatRegistry.removeFromImmortalSet(playerUuid);
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
                        CombatRegistry.recordKill(entity, currentTick, CombatRegistry.getKillAttacker(uuid));
                        KillEnforcer.executeRemoval(living, level);
                        CombatRegistry.confirmDead(uuid);
                    } else if (ticksInKillSet >= 65) {
                        CombatRegistry.recordKill(entity, currentTick, CombatRegistry.getKillAttacker(uuid));
                        KillEnforcer.executeKill(living, level);
                        CombatRegistry.confirmDead(uuid);
                        repairsThisTick++;
                    } else {
                        KillEnforcer.enforceDeathState(living);
                        try {
                            living.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
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
                Entity entity = level.getEntity(uuid);
                if (entity == null) continue;
                RegistryCleaner.deleteFromAllRegistries(entity, level);
                try {
                    entity.setBoundingBox(new AABB(0, 0, 0, 0, 0, 0));
                    entity.noPhysics = true;
                } catch (Throwable ignored) {}
            }

            CombatRegistry.cleanupKillHistory(currentTick);

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
                float dataHealth = entity.getEntityData().get(LivingEntity.DATA_HEALTH_ID);
                if (dataHealth <= 0.0f) {
                    float max = entity.getMaxHealth();
                    if (max <= 0.0f) max = 20.0f;
                    entity.getEntityData().set(LivingEntity.DATA_HEALTH_ID, max);
                }
            } catch (Exception ignored) {}
            try {
                ImmortalEnforcer.setRawDead(entity, false);
                ImmortalEnforcer.setRawDeathTime(entity, 0);
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    public static void onServerLevelTickTail(Object obj) {
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
                    CombatRegistry.recordKill(entity, currentTick, CombatRegistry.getKillAttacker(uuid));
                    KillEnforcer.initiateKill(living, level);
                    KillEnforcer.executeRemoval(living, level);
                    CombatRegistry.confirmDead(uuid);
                    repairsThisTick++;
                } else if (ticksInKillSet >= 65) {
                    CombatRegistry.recordKill(entity, currentTick, CombatRegistry.getKillAttacker(uuid));
                    KillEnforcer.executeKill(living, level);
                    CombatRegistry.confirmDead(uuid);
                    repairsThisTick++;
                } else {
                    KillEnforcer.enforceDeathState(living);
                    try {
                        living.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
                    } catch (Throwable ignored) {}
                    living.noPhysics = true;
                    repairsThisTick++;
                }
            } else if (entity == null) {
                CombatRegistry.confirmDead(uuid);
            }
        }

        for (UUID uuid : CombatRegistry.getImmortalSet()) {
            if (repairsThisTick >= maxRepairsPerTick) break;
            Entity entity = level.getEntity(uuid);
            if (!(entity instanceof LivingEntity)) continue;
            ImmortalEnforcer.enforceImmortality((LivingEntity) entity);
            repairsThisTick++;
        }

        CombatRegistry.cleanupKillHistory(currentTick);
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

    @SuppressWarnings("unchecked")
    public static Object getFilteredById(Object lookup) {
        try {
            Object byId = getByIdField(lookup);
            if (byId == null) return null;
            if (CombatRegistry.getDeadConfirmedSet().isEmpty() && CombatRegistry.getKillSet().isEmpty()) {
                return byId;
            }
            java.util.List<Integer> keysToRemove = new java.util.ArrayList<>();
            try {
                Object entrySet = null;
                try { entrySet = byId.getClass().getMethod("int2ObjectEntrySet").invoke(byId); } catch (Throwable ignored) {}
                if (entrySet == null) {
                    try { entrySet = byId.getClass().getMethod("entrySet").invoke(byId); } catch (Throwable ignored) {}
                }
                if (entrySet instanceof Iterable) {
                    for (Object entry : (Iterable<?>) entrySet) {
                        try {
                            int key;
                            try { key = (int) entry.getClass().getMethod("getIntKey").invoke(entry); }
                            catch (Throwable t) { key = (int) ((java.util.Map.Entry<?,?>) entry).getKey(); }
                            Object val;
                            try { val = entry.getClass().getMethod("getValue").invoke(entry); }
                            catch (Throwable t) { val = ((java.util.Map.Entry<?,?>) entry).getValue(); }
                            if (val instanceof Entity) {
                                Entity entity = (Entity) val;
                                UUID uuid = entity.getUUID();
                                if (CombatRegistry.isDeadConfirmed(uuid) ||
                                    (CombatRegistry.isInKillSet(uuid) && val instanceof LivingEntity
                                        && ((LivingEntity) val).deathTime >= 60)) {
                                    keysToRemove.add(key);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            for (int key : keysToRemove) {
                try { byId.getClass().getMethod("remove", int.class).invoke(byId, key); } catch (Throwable ignored) {}
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
            return CombatRegistry.isInKillSet(u) || CombatRegistry.isDeadConfirmed(u);
        } catch (Exception e) { return false; }
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
                    float max = living.getMaxHealth();
                    if (max <= 0.0f) max = 20.0f;
                    if (f < max) return true;
                }
                if (entity instanceof Player) {
                    Player player = (Player) entity;
                    if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) {
                        float max = ((LivingEntity) entity).getMaxHealth();
                        if (max <= 0.0f) max = 20.0f;
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
}
