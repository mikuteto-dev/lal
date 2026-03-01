package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.core.CombatRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(value = PersistentEntitySectionManager.class, priority = Integer.MAX_VALUE)
public class PersistentEntitySectionManagerMixin<T> {

    @Inject(method = "addEntityUuid", at = @At("HEAD"), cancellable = true)
    private void lal$blockAddEntityUuid(EntityAccess entityAccess, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!(entityAccess instanceof Entity)) return;
            UUID uuid = ((Entity) entityAccess).getUUID();
            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "stopTracking(Lnet/minecraft/world/level/entity/EntityAccess;)V", at = @At("HEAD"), cancellable = true)
    private void lal$blockStopTracking(EntityAccess entityAccess, CallbackInfo ci) {
        try {
            if (!(entityAccess instanceof Entity)) return;
            if (CombatRegistry.isInImmortalSet(((Entity) entityAccess).getUUID())) {
                ci.cancel();
            }
        } catch (Throwable ignored) {}
    }
}
