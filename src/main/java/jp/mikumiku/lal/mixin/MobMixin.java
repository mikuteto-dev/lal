package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.core.CombatRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}
