package jp.mikumiku.lal.mixin;

import java.util.UUID;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import jp.mikumiku.lal.util.MixinUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SynchedEntityData.DataItem.class, priority = Integer.MAX_VALUE)
public abstract class SynchedEntityDataItemMixin<T> {

    @Shadow @Final private EntityDataAccessor<T> accessor;

    @Shadow private T value;

    @Shadow private boolean dirty;

    @Shadow public abstract EntityDataAccessor<T> getAccessor();

    @Shadow public abstract T getValue();

    private static volatile int cachedHealthId = -1;

    private static int getHealthId() {
        if (cachedHealthId < 0) {
            try {
                cachedHealthId = LivingEntity.DATA_HEALTH_ID.getId();
            } catch (Throwable t) {
                cachedHealthId = 9;
            }
        }
        return cachedHealthId;
    }

    private Entity lal$getOwnerEntity() {
        try {
            return EntityMethodHooks.getDataItemOwner(this);
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean lal$isHealthAccessor() {
        try {
            return this.accessor != null && this.accessor.getId() == getHealthId();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean lal$shouldProtect() {
        if (EntityMethodHooks.isBypass()) return false;
        if (!lal$isHealthAccessor()) return false;
        Entity entity = lal$getOwnerEntity();
        if (entity == null) return false;
        if (!(entity instanceof LivingEntity)) return false;
        UUID uuid = entity.getUUID();
        if (CombatRegistry.isInImmortalSet(uuid)) return true;
        if (entity instanceof Player player) {
            if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) return true;
        }
        return false;
    }

    private boolean lal$shouldForceKill() {
        if (EntityMethodHooks.isBypass()) return false;
        if (!lal$isHealthAccessor()) return false;
        Entity entity = lal$getOwnerEntity();
        if (entity == null) return false;
        UUID uuid = entity.getUUID();
        return CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid);
    }

    @Inject(method = "setValue", at = @At("HEAD"), cancellable = true)
    private void lal$protectSetValue(T newValue, CallbackInfo ci) {
        try {
            if (lal$shouldProtect()) {
                if (newValue instanceof Float f) {
                    Object current = this.value;
                    if (current instanceof Float curF && f < curF) {
                        ci.cancel();
                        return;
                    }
                }
            }
            if (lal$shouldForceKill()) {
                if (newValue instanceof Float f && f > 0.0f) {
                    ci.cancel();
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "setDirty", at = @At("HEAD"), cancellable = true)
    private void lal$protectSetDirty(boolean dirtyFlag, CallbackInfo ci) {
        try {
            if (!dirtyFlag) return;
            if (lal$shouldProtect()) {
                if (this.value instanceof Float f) {
                    Entity entity = lal$getOwnerEntity();
                    if (entity instanceof LivingEntity living) {
                        float max = MixinUtil.safeMaxHealth(living);
                        if (f < max) {
                            ci.cancel();
                            return;
                        }
                    }
                }
            }
            if (lal$shouldForceKill()) {
                if (this.value instanceof Float f && f > 0.0f) {
                    ci.cancel();
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "isDirty", at = @At("HEAD"), cancellable = true)
    private void lal$protectIsDirty(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (lal$shouldProtect()) {
                if (this.value instanceof Float f) {
                    Entity entity = lal$getOwnerEntity();
                    if (entity instanceof LivingEntity living) {
                        float max = MixinUtil.safeMaxHealth(living);
                        if (f < max) {
                            cir.setReturnValue(false);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "value", at = @At("HEAD"), cancellable = true)
    private void lal$protectValue(CallbackInfoReturnable<SynchedEntityData.DataValue<T>> cir) {
        try {
            if (lal$shouldProtect()) {
                if (this.value instanceof Float f) {
                    Entity entity = lal$getOwnerEntity();
                    if (entity instanceof LivingEntity living) {
                        float max = MixinUtil.safeMaxHealth(living);
                        if (f < max) {
                            @SuppressWarnings("unchecked")
                            T corrected = (T)(Object)max;
                            cir.setReturnValue(SynchedEntityData.DataValue.create(this.accessor, corrected));
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
