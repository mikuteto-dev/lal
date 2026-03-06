package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.core.CombatRegistry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Mob.class, priority = Integer.MAX_VALUE)
public abstract class MobMixin {

    @Inject(method = "isEffectiveAi", at = @At("HEAD"), cancellable = true)
    private void lal$blockAiIfKilled(CallbackInfoReturnable<Boolean> cir) {
        try {
            Entity self = (Entity)(Object)this;
            if (CombatRegistry.isInKillSet(self) || CombatRegistry.isDeadConfirmed(self.getUUID())) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
    private void lal$blockCheckDespawn(CallbackInfo ci) {
        try {
            Entity self = (Entity)(Object)this;
            if (CombatRegistry.isInImmortalSet(self)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "removeWhenFarAway", at = @At("HEAD"), cancellable = true)
    private void lal$blockRemoveWhenFarAway(double distanceToClosestPlayer, CallbackInfoReturnable<Boolean> cir) {
        try {
            Entity self = (Entity)(Object)this;
            if (CombatRegistry.isInImmortalSet(self)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "shouldDespawnInPeaceful", at = @At("HEAD"), cancellable = true)
    private void lal$blockShouldDespawnInPeaceful(CallbackInfoReturnable<Boolean> cir) {
        try {
            Entity self = (Entity)(Object)this;
            if (CombatRegistry.isInImmortalSet(self)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "isPersistenceRequired", at = @At("HEAD"), cancellable = true)
    private void lal$forceIsPersistenceRequired(CallbackInfoReturnable<Boolean> cir) {
        try {
            Entity self = (Entity)(Object)this;
            if (CombatRegistry.isInImmortalSet(self)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void lal$blockMobInteract(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand, CallbackInfoReturnable<net.minecraft.world.InteractionResult> cir) {
        try {
            Entity self = (Entity)(Object)this;
            java.util.UUID uuid = self.getUUID();
            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
                cir.setReturnValue(net.minecraft.world.InteractionResult.FAIL);
            }
        } catch (Throwable ignored) {}
    }
}
