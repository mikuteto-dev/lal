package jp.mikumiku.lal.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractArrow.class, priority = 0x7FFFFFFF)
public class AbstractArrowMixin {

    @Inject(method = "onHitEntity", at = @At("HEAD"))
    private void lal$onHitEntity(EntityHitResult hitResult, CallbackInfo ci) {
        try {
            jp.mikumiku.lal.transformer.EntityMethodHooks.onArrowHitEntity(this, hitResult);
        } catch (Throwable ignored) {}
    }
}
