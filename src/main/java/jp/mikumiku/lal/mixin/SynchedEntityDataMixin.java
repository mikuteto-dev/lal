package jp.mikumiku.lal.mixin;

import java.util.UUID;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SynchedEntityData.class, priority = Integer.MAX_VALUE)
public abstract class SynchedEntityDataMixin {
    @Shadow @Final private Entity entity;

    @Inject(method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V",
            at = @At("HEAD"), cancellable = true)
    private <T> void lal$protectEntityData(EntityDataAccessor<T> key, T value, CallbackInfo ci) {
        if (lal$shouldBlock(key, value)) ci.cancel();
    }

    @Inject(method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V",
            at = @At("HEAD"), cancellable = true)
    private <T> void lal$protectEntityDataInternal(EntityDataAccessor<T> key, T value, boolean force, CallbackInfo ci) {
        if (lal$shouldBlock(key, value)) ci.cancel();
    }

    private <T> boolean lal$shouldBlock(EntityDataAccessor<T> key, T value) {
        try {
            if (this.entity == null) return false;
            if (this.entity.level() == null) return false;
            if (EntityMethodHooks.isBypass()) return false;
            UUID uuid = this.entity.getUUID();
            if (value instanceof Float f && this.entity instanceof LivingEntity) {
                try {
                    if (key.getId() != LivingEntity.DATA_HEALTH_ID.getId()) return false;
                } catch (Exception e) {
                    return false;
                }
                if (CombatRegistry.isInImmortalSet(uuid)) {
                    LivingEntity living = (LivingEntity) this.entity;
                    float max = living.getMaxHealth();
                    if (max <= 0.0f) max = 20.0f;
                    if (f < max) return true;
                }
                if (this.entity instanceof Player player) {
                    if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) {
                        float max = ((LivingEntity) this.entity).getMaxHealth();
                        if (max <= 0.0f) max = 20.0f;
                        if (f < max) return true;
                    }
                }
                if (f > 0 && (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid))) {
                    return true;
                }
                return false;
            }
            if (value instanceof Pose pose && pose == Pose.DYING) {
                if (CombatRegistry.isInImmortalSet(uuid)) return true;
                if (this.entity instanceof Player player) {
                    if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) return true;
                }
                if (this.entity.level().isClientSide() && this.entity instanceof LivingEntity living) {
                    try {
                        float h = living.getEntityData().get(LivingEntity.DATA_HEALTH_ID);
                        if (h > 0) return true;
                    } catch (Exception ignored) {}
                }
            }
            if (value instanceof Boolean bool && bool && this.entity instanceof LivingEntity) {
                int id = key.getId();
                boolean isVanillaBoolean = (id == 3 || id == 4 || id == 5 || id == 10);
                if (!isVanillaBoolean) {
                    if (CombatRegistry.isInImmortalSet(uuid)) return true;
                    if (this.entity instanceof Player player) {
                        if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) return true;
                    }
                }
            }
            if (value instanceof Integer intVal && this.entity instanceof LivingEntity) {
                boolean isProtectedPlayer = this.entity instanceof Player player2
                        && !CombatRegistry.isInKillSet(uuid)
                        && LALSwordItem.hasLALEquipment(player2);
                if ((CombatRegistry.isInImmortalSet(uuid) || isProtectedPlayer) && intVal <= 0) {
                    int id = key.getId();
                    if (id > 15) {
                        try {
                            Object cur = this.entity.getEntityData().get(key);
                            if (cur instanceof Integer curInt && curInt > 0) return true;
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
