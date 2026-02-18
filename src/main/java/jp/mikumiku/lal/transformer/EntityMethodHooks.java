package jp.mikumiku.lal.transformer;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.enforcement.ImmortalEnforcer;
import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class EntityMethodHooks {
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static final AtomicLong hookCallCount = new AtomicLong(0);

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
        recordHookCall();
        if (BYPASS.get()) return false;
        if (!(obj instanceof LivingEntity)) return false;
        LivingEntity entity = (LivingEntity) obj;
        try {
            UUID uuid = entity.getUUID();

            if (CombatRegistry.isDeadConfirmed(uuid)) {
                return true;
            }

        } catch (Exception ignored) {}
        return false;
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
            return CombatRegistry.isInImmortalSet(entity);
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


    private static float sanitizeHealth(float value, LivingEntity entity) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return Math.max(entity.getMaxHealth(), 20.0f);
        }
        return value;
    }
}
