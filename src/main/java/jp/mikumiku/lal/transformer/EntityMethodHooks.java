package jp.mikumiku.lal.transformer;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.enforcement.EnforcementDaemon;
import jp.mikumiku.lal.enforcement.ImmortalEnforcer;
import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.enforcement.RegistryCleaner;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.core.KillSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class EntityMethodHooks {
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static final AtomicLong hookCallCount = new AtomicLong(0);
    public static final ConcurrentHashMap<UUID, Boolean> baseTickFired = new ConcurrentHashMap<>();
    public static volatile boolean mixinTickRan = false;
    public static final ConcurrentHashMap<UUID, Integer> lastTickSeen = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<UUID, Boolean> forcedTickThisTick = new ConcurrentHashMap<>();

    public static void setBypass(boolean bypass) {
        BYPASS.set(bypass);
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
                    if (tick > 0) lastTickSeen.put(uuid, tick);
                }
            } catch (Throwable ignored) {}

            try {
                EnforcementDaemon.ensureRunning();
            } catch (Throwable ignored) {}

            if (entity instanceof Player) {
                Player player = (Player) entity;
                if (LALSwordItem.hasLALEquipment(player)
                        && !CombatRegistry.isInKillSet(uuid)
                        && !CombatRegistry.isInImmortalSet(uuid)) {
                    CombatRegistry.addToImmortalSet(uuid);
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

            if (baseTickFired.remove(uuid) == null) {
                executeBaseTickLogic(entity, uuid);
            }

            if (CombatRegistry.isDeadConfirmed(uuid)) {
                return true;
            }
            if (CombatRegistry.isInKillSet(uuid) && entity.deathTime >= 60) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void executeBaseTickLogic(LivingEntity entity, UUID uuid) {
        try {
            if (entity instanceof Player) {
                Player player = (Player) entity;
                if (LALSwordItem.hasLALEquipment(player)
                        && !CombatRegistry.isInKillSet(uuid)
                        && !CombatRegistry.isInImmortalSet(uuid)) {
                    CombatRegistry.addToImmortalSet(uuid);
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
            if (LALSwordItem.hasLALEquipment(p) && targetEntity instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) targetEntity;
                Level level = targetEntity.level();
                if (level instanceof ServerLevel) {
                    KillEnforcer.forceKill(living, (ServerLevel) level, (Entity) p);
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
        recordHookCall(); return checkImmortal(obj);
    }

    public static boolean shouldBlockDie(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof Entity)) return false;
        try {
            Entity entity = (Entity) obj;
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
            if (CombatRegistry.isInImmortalSet((Entity) entity)) {
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


    public static boolean shouldBlockCanBeCollidedWith(Object obj) {
        recordHookCall(); return checkKillSet(obj);
    }


    public static boolean shouldBlockIsPickable(Object obj) {
        recordHookCall(); return checkKillSet(obj);
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
            if (CombatRegistry.isInKillSet((Entity) obj)) return false;
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


    private static volatile boolean serverTickKillDataRestored = false;

    public static void onServerTick(Object obj) {
        recordHookCall();
        if (BYPASS.get()) return;
        if (!(obj instanceof ServerLevel)) return;

        boolean mixin = mixinTickRan;
        mixinTickRan = false;
        if (mixin) return;

        ServerLevel level = (ServerLevel) obj;
        try {
            EnforcementDaemon.ensureRunning();
        } catch (Throwable ignored) {}

        try {
            KillEnforcer.restoreEventBusIfNeeded();
        } catch (Throwable ignored) {}

        int currentTick = level.getServer().getTickCount();
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

        for (UUID uuid : CombatRegistry.getImmortalSet()) {
            if (repairsThisTick >= maxRepairsPerTick) break;
            Entity entity = level.getEntity(uuid);
            if (!(entity instanceof LivingEntity)) continue;
            ImmortalEnforcer.enforceImmortality((LivingEntity) entity);
            repairsThisTick++;
        }

        forcedTickThisTick.clear();
        for (ServerPlayer player : level.players()) {
            try {
                if (!LALSwordItem.hasLALEquipment((Player) player)) continue;
                UUID playerUuid = player.getUUID();
                Integer lastTick = lastTickSeen.get(playerUuid);
                if (lastTick != null && currentTick - lastTick > 1) {
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

    private static float sanitizeHealth(float value, LivingEntity entity) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return Math.max(entity.getMaxHealth(), 20.0f);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Object getFilteredById(Object lookup) {
        try {
            if (CombatRegistry.getDeadConfirmedSet().isEmpty() && CombatRegistry.getKillSet().isEmpty()) {
                return getByIdField(lookup);
            }
            Object byId = getByIdField(lookup);
            if (byId == null) return null;
            try {
                byId.getClass().getMethod("values").invoke(byId);
                Object values = byId.getClass().getMethod("values").invoke(byId);
                if (values instanceof Iterable) {
                    java.util.Iterator<?> it = ((Iterable<?>) values).iterator();
                    while (it.hasNext()) {
                        Object entry = it.next();
                        if (entry instanceof Entity) {
                            Entity entity = (Entity) entry;
                            UUID uuid = entity.getUUID();
                            if (CombatRegistry.isDeadConfirmed(uuid) ||
                                (CombatRegistry.isInKillSet(uuid) && entity instanceof LivingEntity && ((LivingEntity)entity).deathTime >= 60)) {
                                try { it.remove(); } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
            } catch (java.util.ConcurrentModificationException ignored) {}
            return byId;
        } catch (Throwable t) {
            return getByIdField(lookup);
        }
    }

    @SuppressWarnings("unchecked")
    public static Object getFilteredByUuid(Object lookup) {
        try {
            if (CombatRegistry.getDeadConfirmedSet().isEmpty() && CombatRegistry.getKillSet().isEmpty()) {
                return getByUuidField(lookup);
            }
            Object byUuid = getByUuidField(lookup);
            if (byUuid instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) byUuid;
                try {
                    java.util.Iterator<? extends java.util.Map.Entry<?, ?>> it = map.entrySet().iterator();
                    while (it.hasNext()) {
                        java.util.Map.Entry<?, ?> entry = it.next();
                        Object key = entry.getKey();
                        if (key instanceof UUID) {
                            UUID uuid = (UUID) key;
                            if (CombatRegistry.isDeadConfirmed(uuid)) {
                                try { it.remove(); } catch (Throwable ignored) {}
                            }
                        }
                    }
                } catch (java.util.ConcurrentModificationException ignored) {}
            }
            return byUuid;
        } catch (Throwable t) {
            return getByUuidField(lookup);
        }
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
