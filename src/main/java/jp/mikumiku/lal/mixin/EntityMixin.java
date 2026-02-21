package jp.mikumiku.lal.mixin;

import java.lang.reflect.Field;
import java.util.UUID;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={Entity.class}, priority=0x7FFFFFFF)
public abstract class EntityMixin {
    public EntityMixin() {
        super();
    }

    @Inject(method={"isAlive"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$isAlive(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        Float forced = CombatRegistry.getForcedHealth(uuid);
        if (forced != null) {
            cir.setReturnValue(forced.floatValue() > 0.0f);
            return;
        }
        if (CombatRegistry.isInImmortalSet(self)) {
            cir.setReturnValue(true);
            return;
        }
        if (!EntityMethodHooks.isBypass() && (CombatRegistry.isInKillSet(self) || CombatRegistry.isDeadConfirmed(uuid))) {
            cir.setReturnValue(false);
            return;
        }
        if (self.level().isClientSide() && self instanceof LivingEntity) {
            LivingEntity living = (LivingEntity)self;
            try {
                float dataHealth = ((Float)living.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
                if (dataHealth > 0.0f) {
                    cir.setReturnValue(true);
                }
            }
            catch (Exception exception) {
                }
        }
    }

    @Inject(method={"isRemoved"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$isRemoved(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        Float forced = CombatRegistry.getForcedHealth(uuid);
        if (forced != null && !EntityMethodHooks.isBypass()) {
            cir.setReturnValue(forced.floatValue() <= 0.0f);
            return;
        }
        if (CombatRegistry.isInImmortalSet(self)) {
            cir.setReturnValue(false);
            return;
        }
        if (!EntityMethodHooks.isBypass() && (CombatRegistry.isInKillSet(self) || CombatRegistry.isDeadConfirmed(uuid))) {
            cir.setReturnValue(true);
            return;
        }
        if (self.level().isClientSide() && self instanceof LivingEntity) {
            LivingEntity living = (LivingEntity)self;
            try {
                float dataHealth = ((Float)living.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
                if (dataHealth > 0.0f) {
                    cir.setReturnValue(false);
                }
            }
            catch (Exception exception) {
                }
        }
    }

    @Inject(method={"getRemovalReason"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$getRemovalReason(CallbackInfoReturnable<Entity.RemovalReason> cir) {
        Float forced;
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        if (!EntityMethodHooks.isBypass() && (forced = CombatRegistry.getForcedHealth(uuid)) != null) {
            cir.setReturnValue(forced.floatValue() <= 0.0f ? Entity.RemovalReason.KILLED : null);
            return;
        }
        if (CombatRegistry.isInImmortalSet(self)) {
            cir.setReturnValue(null);
            return;
        }
        if (!EntityMethodHooks.isBypass() && (CombatRegistry.isInKillSet(self) || CombatRegistry.isDeadConfirmed(uuid))) {
            cir.setReturnValue(Entity.RemovalReason.KILLED);
            return;
        }
        if (self.level().isClientSide() && self instanceof LivingEntity) {
            LivingEntity living = (LivingEntity)self;
            try {
                float dataHealth = ((Float)living.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
                if (dataHealth > 0.0f) {
                    cir.setReturnValue(null);
                }
            }
            catch (Exception exception) {
                }
        }
    }

    @Inject(method={"setPose"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onSetPose(Pose pose, CallbackInfo ci) {
        if (pose != Pose.DYING) {
            return;
        }
        if (EntityMethodHooks.isBypass()) {
            return;
        }
        Entity self = (Entity)(Object)this;
        if (CombatRegistry.isInImmortalSet(self)) {
            ci.cancel();
            return;
        }
        if (self.level().isClientSide() && self instanceof LivingEntity) {
            LivingEntity living = (LivingEntity)self;
            try {
                float dataHealth = ((Float)living.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
                if (dataHealth > 0.0f) {
                    ci.cancel();
                }
            }
            catch (Exception exception) {
                }
        }
    }

    @Inject(method={"setRemoved"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onSetRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
            ci.cancel();
            return;
        }
        if (reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            return;
        }
        if (CombatRegistry.isInImmortalSet(self)) {
            ci.cancel();
        }
    }

    @Inject(method={"setLevelCallback"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onSetLevelCallback(EntityInLevelCallback callback, CallbackInfo ci) {
        if (EntityMethodHooks.isBypass()) return;
        Entity self = (Entity)(Object)this;
        if (!CombatRegistry.isInImmortalSet(self)) return;
        if (callback == null || callback == EntityInLevelCallback.NULL) {
            ci.cancel();
        }
    }

    @Inject(method={"kill"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onKill(CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (CombatRegistry.isInImmortalSet(self)) {
            ci.cancel();
        }
    }

    @Inject(method={"discard"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onDiscard(CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (CombatRegistry.isInImmortalSet(self)) {
            ci.cancel();
        }
    }

    @Inject(method={"remove"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onRemove(Entity.RemovalReason reason, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            return;
        }
        if (CombatRegistry.isInImmortalSet(self)) {
            ci.cancel();
        }
    }

    @Inject(method={"canBeCollidedWith"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$canBeCollidedWith(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method={"isPickable"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$isPickable(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method={"getBoundingBox"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$getBoundingBox(CallbackInfoReturnable<AABB> cir) {
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
            if (self instanceof LivingEntity) {
                LivingEntity living = (LivingEntity)self;
                if (living.deathTime > 0 && living.deathTime < 60 && CombatRegistry.isInKillSet(uuid)) {
                    return;
                }
            }
            cir.setReturnValue(new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        }
    }

    @Inject(method={"setPosRaw"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$setPosRaw(double x, double y, double z, CallbackInfo ci) {
        if (EntityMethodHooks.isBypass()) {
            return;
        }
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
            ci.cancel();
        }
    }

    @Inject(method={"move"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$move(CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
            ci.cancel();
            return;
        }
        if (ci.isCancellable() && ci.isCancelled() && EntityMixin.lal$isProtectedEntity(self, uuid)) {
            EntityMixin.lal$forceUncancel(ci);
        }
    }

    @Inject(method={"tick"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$tick(CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        boolean shouldCancel = false;
        if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
            if (self instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) self;
                if (living.deathTime >= 60 || CombatRegistry.isDeadConfirmed(uuid)) {
                    shouldCancel = true;
                }
            } else {
                shouldCancel = true;
            }
        }
        if (shouldCancel) {
            ci.cancel();
            return;
        }
        if (ci.isCancellable() && ci.isCancelled() && EntityMixin.lal$isProtectedEntity(self, uuid)) {
            EntityMixin.lal$forceUncancel(ci);
        }
    }

    private static boolean lal$isProtectedEntity(Entity self, UUID uuid) {
        if (CombatRegistry.isInImmortalSet(self)) {
            return true;
        }
        if (self instanceof Player) {
            Player player = (Player)self;
            return LALSwordItem.hasLALEquipment(player) && !CombatRegistry.isInKillSet(uuid);
        }
        return false;
    }

    private static void lal$forceUncancel(CallbackInfo ci) {
        try {
            Field f = CallbackInfo.class.getDeclaredField("cancelled");
            f.setAccessible(true);
            f.setBoolean(ci, false);
        }
        catch (Exception exception) {
        }
    }

    @Inject(method={"setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onSetDeltaMovement(Vec3 motion, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (!CombatRegistry.isInImmortalSet(self)) {
            return;
        }
        double speed = motion.horizontalDistance();
        if (speed > 3.0) {
            ci.cancel();
        }
        if (Math.abs(motion.y) > 5.0) {
            ci.cancel();
        }
    }

    @Inject(method={"push(DDD)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onPush(double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        if (CombatRegistry.isInImmortalSet(self)) {
            ci.cancel();
        }
    }

    @Inject(method={"shouldBeSaved"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$shouldBeSaved(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity)(Object)this;
        if (CombatRegistry.isInImmortalSet(self)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method={"playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$blockKillSetSound(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        UUID uuid = self.getUUID();
        if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
            ci.cancel();
        }
    }
}

