package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.core.CombatRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EntityTickList.class, priority = Integer.MAX_VALUE)
public class EntityTickListMixin {

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void lal$blockRemove(Entity entity, CallbackInfo ci) {
        try {
            if (CombatRegistry.isInImmortalSet(entity)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {}
    }
}
