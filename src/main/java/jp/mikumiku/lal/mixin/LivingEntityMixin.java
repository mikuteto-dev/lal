package jp.mikumiku.lal.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.enforcement.EnforcementDaemon;
import jp.mikumiku.lal.enforcement.ImmortalEnforcer;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={LivingEntity.class}, priority=0x7FFFFFFF)
public abstract class LivingEntityMixin {
    public LivingEntityMixin() {
        super();
    }

    @Inject(method={"tick"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$livingTick(CallbackInfo ci) {
        Player player;
        LivingEntity self = (LivingEntity)(Object)this;
        UUID uuid = self.getUUID();

        if (EntityMethodHooks.forcedTickThisTick.containsKey(uuid)) {
            EntityMethodHooks.normalTickAttempted.put(uuid, Boolean.TRUE);
            ci.cancel();
            return;
        }

        if (EntityMethodHooks.baseTickFired.remove(uuid) == null) {
            try {
                if (self instanceof Player) {
                    Player p = (Player) self;
                    boolean hasEquip = LALSwordItem.hasLALEquipment(p);
                    if (hasEquip && !CombatRegistry.isInKillSet(uuid) && !CombatRegistry.isInImmortalSet(uuid)) {
                        CombatRegistry.addToImmortalSet(uuid);
                    } else if (!hasEquip && CombatRegistry.isInImmortalSet(uuid)) {
                        CombatRegistry.removeFromImmortalSet(uuid);
                    }
                }
                try { EnforcementDaemon.trackEntity(self); } catch (Exception ignored) {}
                if (CombatRegistry.isInImmortalSet(uuid)) {
                    try {
                        ImmortalEnforcer.setRawDeathTime(self, 0);
                        ImmortalEnforcer.setRawDead(self, false);
                        ImmortalEnforcer.setRawHurtTime(self, 0);
                        self.deathTime = 0;
                        self.hurtTime = 0;
                        if (self.getPose() == Pose.DYING) {
                            self.setPose(Pose.STANDING);
                        }
                        float max = self.getMaxHealth();
                        if (max <= 0.0f) max = 20.0f;
                        ImmortalEnforcer.setRawHealth(self, max);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        if ((CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) && (self.deathTime >= 60 || CombatRegistry.isDeadConfirmed(uuid))) {
            ci.cancel();
            return;
        }
        if (ci.isCancellable() && ci.isCancelled()) {
            Player playerRef = self instanceof Player ? (Player) self : null;
            if (CombatRegistry.isInImmortalSet((Entity)self) ||
                    (playerRef != null && LALSwordItem.hasLALEquipment(playerRef) && !CombatRegistry.isInKillSet(uuid))) {
                try {
                    Field f = CallbackInfo.class.getDeclaredField("cancelled");
                    f.setAccessible(true);
                    f.setBoolean(ci, false);
                }
                catch (Exception e) {
                }
            }
        }
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            try {
                ImmortalEnforcer.setRawDeathTime(self, 0);
                ImmortalEnforcer.setRawDead(self, false);
                ImmortalEnforcer.setRawHurtTime(self, 0);
            }
            catch (Exception e) {
            }
            self.deathTime = 0;
            self.hurtTime = 0;
            if (self.getPose() == Pose.DYING) {
                self.setPose(Pose.STANDING);
                self.refreshDimensions();
            }
            try {
                float max = self.getMaxHealth();
                if (max <= 0.0f) {
                    max = 20.0f;
                }
                self.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(max));
                self.setHealth(max);
                ImmortalEnforcer.setRawHealth(self, max);
            }
            catch (Exception e) {
            }
            self.noPhysics = false;
            self.setNoGravity(false);
        }
        if (self.level().isClientSide() && self instanceof Player && LALSwordItem.hasLALEquipment(player = (Player)self) && !CombatRegistry.isInKillSet(uuid)) {
            if (self.deathTime > 0) {
                self.deathTime = 0;
            }
            if (self.hurtTime > 20) {
                self.hurtTime = 0;
            }
            if (self.getPose() == Pose.DYING) {
                self.setPose(Pose.STANDING);
                self.refreshDimensions();
            }
            try {
                float dataHealth = ((Float)self.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
                if (dataHealth <= 0.0f) {
                    float max = self.getMaxHealth();
                    if (max <= 0.0f) {
                        max = 20.0f;
                    }
                    EntityMethodHooks.setBypass(true);
                    try {
                        self.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(max));
                    }
                    finally {
                        EntityMethodHooks.setBypass(false);
                    }
                }
            }
            catch (Exception e) {
            }
            self.noPhysics = false;
            self.setNoGravity(false);
            try {
                LivingEntityMixin.lal$resetHostileFlags(self);
            }
            catch (Exception e) {
            }
        }
    }

    @Inject(method={"baseTick"}, at={@At(value="HEAD")})
    private void lal$onBaseTick(CallbackInfo ci) {
        Player player;
        LivingEntity self = (LivingEntity)(Object)this;
        if (self.level().isClientSide() && self instanceof Player
                && LALSwordItem.hasLALEquipment((Player) self)
                && !CombatRegistry.isInKillSet(self.getUUID())) {
            EntityMethodHooks.clientLastTickedNano = System.nanoTime();
        }
        if (self instanceof Player) {
            Player p = (Player)self;
            UUID uid = self.getUUID();
            boolean hasEquip = LALSwordItem.hasLALEquipment(p);
            if (hasEquip && !CombatRegistry.isInKillSet(uid) && !CombatRegistry.isInImmortalSet(uid)) {
                CombatRegistry.addToImmortalSet(uid);
            } else if (!hasEquip && CombatRegistry.isInImmortalSet(uid)) {
                CombatRegistry.removeFromImmortalSet(uid);
            }
        }
        if (!CombatRegistry.isInImmortalSet((Entity)self)) {
            return;
        }
        if (self.deathTime > 0) {
            self.deathTime = 0;
        }
        self.hurtTime = 0;
        if (self.getArrowCount() > 0) {
            EntityMethodHooks.setBypass(true);
            try { self.setArrowCount(0); } finally { EntityMethodHooks.setBypass(false); }
        }
        if (self.getPose() == Pose.DYING) {
            self.setPose(Pose.STANDING);
            self.refreshDimensions();
        }
        self.noPhysics = false;
        self.setNoGravity(false);
        if (self instanceof Player) {
            player = (Player)self;
            try {
                float dataHealth = ((Float)self.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
                if (dataHealth <= 0.0f) {
                    float max = self.getMaxHealth();
                    if (max <= 0.0f) {
                        max = 20.0f;
                    }
                    self.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(max));
                    self.setHealth(max);
                }
            }
            catch (Exception e) {
            }
            try {
                float max = self.getMaxHealth();
                if (max <= 0.0f) {
                    max = 20.0f;
                }
                ImmortalEnforcer.setRawHealth(self, max);
                ImmortalEnforcer.setRawDead(self, false);
                ImmortalEnforcer.setRawDeathTime(self, 0);
                ImmortalEnforcer.setRawHurtTime(self, 0);
            }
            catch (Exception e) {
            }
        }
    }

    @Inject(method={"getHealth"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$getHealth(CallbackInfoReturnable<Float> cir) {
        Player player;
        LivingEntity self = (LivingEntity)(Object)this;
        UUID uuid = self.getUUID();
        Float forced = CombatRegistry.getForcedHealth(uuid);
        if (forced != null) {
            cir.setReturnValue(forced);
            return;
        }
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            float max = self.getMaxHealth();
            cir.setReturnValue(Float.valueOf(max > 0.0f ? max : 20.0f));
            return;
        }
        if (!EntityMethodHooks.isBypass() && (CombatRegistry.isInKillSet((Entity)self) || CombatRegistry.isDeadConfirmed(uuid))) {
            cir.setReturnValue(Float.valueOf(0.0f));
            return;
        }
        if (self instanceof Player && LALSwordItem.hasLALEquipment(player = (Player)self)) {
            try {
                float dataHealth = ((Float)self.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
                if (dataHealth <= 0.0f) {
                    float max = self.getMaxHealth();
                    cir.setReturnValue(Float.valueOf(max > 0.0f ? max : 20.0f));
                    return;
                }
            }
            catch (Exception exception) {
            }
        }
        if (self.level().isClientSide()) {
            try {
                float dataHealth = ((Float)self.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
                if (dataHealth > 0.0f) {
                    cir.setReturnValue(Float.valueOf(dataHealth));
                }
            }
            catch (Exception exception) {
            }
        }
    }

    @Inject(method={"isDeadOrDying"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$isDeadOrDying(CallbackInfoReturnable<Boolean> cir) {
        Player player;
        LivingEntity self = (LivingEntity)(Object)this;
        UUID uuid = self.getUUID();
        Float forced = CombatRegistry.getForcedHealth(uuid);
        if (forced != null) {
            cir.setReturnValue(forced.floatValue() <= 0.0f);
            return;
        }
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            cir.setReturnValue(false);
            return;
        }
        if (!EntityMethodHooks.isBypass() && (CombatRegistry.isInKillSet((Entity)self) || CombatRegistry.isDeadConfirmed(uuid))) {
            cir.setReturnValue(true);
            return;
        }
        if (self instanceof Player && LALSwordItem.hasLALEquipment(player = (Player)self)) {
            try {
                float dataHealth;
                if (self.deathTime > 0) {
                    self.deathTime = 0;
                }
                if (self.getPose() == Pose.DYING) {
                    self.setPose(Pose.STANDING);
                }
                if ((dataHealth = ((Float)self.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue()) <= 0.0f) {
                    float max = self.getMaxHealth();
                    if (max <= 0.0f) {
                        max = 20.0f;
                    }
                    self.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(max));
                }
            }
            catch (Exception exception) {
            }
            cir.setReturnValue(false);
            return;
        }
        if (self.level().isClientSide()) {
            try {
                float dataHealth = ((Float)self.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
                if (dataHealth > 0.0f) {
                    cir.setReturnValue(false);
                }
            }
            catch (Exception exception) {
            }
        }
    }

    @Inject(method={"hurt"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Player player;
        LivingEntity self = (LivingEntity)(Object)this;
        if (self instanceof Player && !LALSwordItem.hasLALEquipment((Player)self)) {
            CombatRegistry.removeFromImmortalSet(self.getUUID());
        }
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            if (source.getMsgId().equals("lal_attack")) {
                return;
            }
            cir.setReturnValue(false);
            return;
        }
        if (self instanceof Player && LALSwordItem.hasLALEquipment(player = (Player)self) && !source.getMsgId().equals("lal_attack")) {
            cir.setReturnValue(false);
            return;
        }
        if (CombatRegistry.isInKillSet((Entity)self)) {
            self.invulnerableTime = 0;
            self.setInvulnerable(false);
        }
    }

    @Inject(method={"die"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onDie(DamageSource source, CallbackInfo ci) {
        Player player;
        LivingEntity self = (LivingEntity)(Object)this;
        if (EntityMethodHooks.isBypass()) {
            return;
        }
        if (self instanceof Player && !LALSwordItem.hasLALEquipment((Player)self)) {
            CombatRegistry.removeFromImmortalSet(self.getUUID());
        }
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            ci.cancel();
            return;
        }
        if (CombatRegistry.isInKillSet((Entity)self) || CombatRegistry.isDeadConfirmed(self.getUUID())) {
            ci.cancel();
            return;
        }
        if (self instanceof Player && LALSwordItem.hasLALEquipment(player = (Player)self) && !source.getMsgId().equals("lal_attack")) {
            ci.cancel();
        }
    }

    @Inject(method={"getMaxHealth"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$getMaxHealthKill(CallbackInfoReturnable<Float> cir) {
        if (EntityMethodHooks.isBypass()) return;
        LivingEntity self = (LivingEntity)(Object)this;
        if (CombatRegistry.isInKillSet((Entity)self) || CombatRegistry.isDeadConfirmed(self.getUUID())) {
            cir.setReturnValue(20.0f);
        }
    }

    @Inject(method={"getMaxHealth"}, at={@At(value="RETURN")}, cancellable=true)
    private void lal$getMaxHealth(CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            float val = cir.getReturnValue();
            if (val < 20.0f) {
                cir.setReturnValue(20.0f);
            }
        }
    }

    @Inject(method={"setHealth"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onSetHealth(float health, CallbackInfo ci) {
        Player player;
        LivingEntity self = (LivingEntity)(Object)this;
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            float max = self.getMaxHealth();
            if (max <= 0.0f) {
                max = 20.0f;
            }
            if (health < max || Float.isNaN(health) || Float.isInfinite(health)) {
                try {
                    self.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(max));
                }
                catch (Exception exception) {
                    }
                ci.cancel();
            }
            return;
        }
        if (self instanceof Player && LALSwordItem.hasLALEquipment(player = (Player)self)) {
            float max = self.getMaxHealth();
            if (max <= 0.0f) {
                max = 20.0f;
            }
            if (health < max || Float.isNaN(health) || Float.isInfinite(health)) {
                try {
                    self.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(max));
                }
                catch (Exception exception) {
                    }
                ci.cancel();
            }
        }
    }

    @Inject(method={"tickDeath"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onTickDeath(CallbackInfo ci) {
        Player player;
        LivingEntity self = (LivingEntity)(Object)this;
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            ci.cancel();
            return;
        }
        if (self instanceof Player && LALSwordItem.hasLALEquipment(player = (Player)self)) {
            self.deathTime = 0;
            self.hurtTime = 0;
            if (self.getPose() == Pose.DYING) {
                self.setPose(Pose.STANDING);
            }
            try {
                float max = self.getMaxHealth();
                if (max <= 0.0f) {
                    max = 20.0f;
                }
                self.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(max));
                self.setHealth(max);
            }
            catch (Exception e) {
            }
            try {
                float max = self.getMaxHealth();
                if (max <= 0.0f) {
                    max = 20.0f;
                }
                ImmortalEnforcer.setRawHealth(self, max);
                ImmortalEnforcer.setRawDead(self, false);
                ImmortalEnforcer.setRawDeathTime(self, 0);
                ImmortalEnforcer.setRawHurtTime(self, 0);
            }
            catch (Exception e) {
            }
            self.noPhysics = false;
            ci.cancel();
        }
    }

    @Inject(method={"actuallyHurt"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onActuallyHurt(DamageSource source, float amount, CallbackInfo ci) {
        Player player;
        LivingEntity self = (LivingEntity)(Object)this;
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            if (source.getMsgId().equals("lal_attack")) {
                return;
            }
            ci.cancel();
            return;
        }
        if (self instanceof Player && LALSwordItem.hasLALEquipment(player = (Player)self) && !source.getMsgId().equals("lal_attack")) {
            ci.cancel();
        }
    }

    @Inject(method={"removeAllEffects"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onRemoveAllEffects(CallbackInfoReturnable<Boolean> cir) {
        if (EntityMethodHooks.isBypass()) {
            return;
        }
        LivingEntity self = (LivingEntity)(Object)this;
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method={"knockback"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onKnockback(double strength, double ratioX, double ratioZ, CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            ci.cancel();
        }
    }

    @Inject(method={"setArrowCount"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onSetArrowCount(int count, CallbackInfo ci) {
        if (EntityMethodHooks.isBypass()) return;
        LivingEntity self = (LivingEntity)(Object)this;
        if (CombatRegistry.isInImmortalSet((Entity)self)) {
            ci.cancel();
        }
    }

    @Inject(method={"onSyncedDataUpdated"}, at={@At(value="HEAD")})
    private void lal$interceptHealthUpdate(EntityDataAccessor<?> key, CallbackInfo ci) {
        try {
            if (EntityMethodHooks.isBypass()) return;
            LivingEntity self = (LivingEntity)(Object)this;
            try {
                if (key.getId() != LivingEntity.DATA_HEALTH_ID.getId()) return;
            } catch (Exception e) {
                return;
            }
            UUID uuid = self.getUUID();
            boolean isProtected = CombatRegistry.isInImmortalSet((Entity)self) ||
                (self instanceof Player p && LALSwordItem.hasLALEquipment(p) && !CombatRegistry.isInKillSet(uuid));
            if (!isProtected) return;
            float current = self.getEntityData().get(LivingEntity.DATA_HEALTH_ID);
            float max = self.getMaxHealth();
            if (max <= 0.0f) max = 20.0f;
            if (current < max) {
                EntityMethodHooks.setBypass(true);
                try {
                    self.getEntityData().set(LivingEntity.DATA_HEALTH_ID, max);
                } finally {
                    EntityMethodHooks.setBypass(false);
                }
                try {
                    ImmortalEnforcer.setRawHealth(self, max);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method={"shouldDropLoot"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$shouldDropLoot(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (CombatRegistry.isInKillSet((Entity)self) || CombatRegistry.isDeadConfirmed(self.getUUID())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method={"shouldDropExperience"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$shouldDropExperience(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (CombatRegistry.isInKillSet((Entity)self) || CombatRegistry.isDeadConfirmed(self.getUUID())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method={"tick"}, at={@At(value="TAIL")})
    private void lal$livingTickTail(CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        boolean isProtected = CombatRegistry.isInImmortalSet((Entity)self);
        if (!isProtected && self instanceof Player) {
            Player player = (Player)self;
            isProtected = LALSwordItem.hasLALEquipment(player) && !CombatRegistry.isInKillSet(self.getUUID());
        }
        if (!isProtected) {
            return;
        }
        if (self.deathTime > 0) {
            self.deathTime = 0;
        }
        if (self.hurtTime > 0) {
            self.hurtTime = 0;
        }
        if (self.getArrowCount() > 0) {
            EntityMethodHooks.setBypass(true);
            try { self.setArrowCount(0); } finally { EntityMethodHooks.setBypass(false); }
        }
        if (self.getPose() == Pose.DYING) {
            self.setPose(Pose.STANDING);
            self.refreshDimensions();
        }
        self.noPhysics = false;
        self.setNoGravity(false);
        try {
            float dataHealth = ((Float)self.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
            if (dataHealth <= 0.0f) {
                float max = self.getMaxHealth();
                if (max <= 0.0f) {
                    max = 20.0f;
                }
                self.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(max));
            }
        }
        catch (Exception exception) {
        }
        try {
            ImmortalEnforcer.setRawDead(self, false);
            ImmortalEnforcer.setRawDeathTime(self, 0);
        }
        catch (Exception exception) {
        }
        if (self.level().isClientSide() && self instanceof Player) {
            try {
                LivingEntityMixin.lal$resetHostileFlags(self);
            }
            catch (Exception exception) {
            }
        }
    }

    private static void lal$resetHostileFlags(LivingEntity entity) {
        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            Field[] fields;
            try {
                fields = clazz.getDeclaredFields();
            }
            catch (Throwable t) {
                clazz = clazz.getSuperclass();
                continue;
            }
            for (Field f : fields) {
                String name;
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != Boolean.TYPE || !(name = f.getName().toLowerCase()).contains("novel") && !name.contains("dead") && !name.contains("death") && !name.contains("kill") && !name.contains("dying") && !name.contains("muteki")) continue;
                try {
                    f.setAccessible(true);
                    if (!f.getBoolean(entity)) continue;
                    f.setBoolean(entity, false);
                }
                catch (Exception exception) {
                    }
            }
            if (clazz.getName().startsWith("net.minecraft.")) break;
            clazz = clazz.getSuperclass();
        }
    }
}

