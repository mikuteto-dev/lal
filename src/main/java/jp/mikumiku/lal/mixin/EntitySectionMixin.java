package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.core.CombatRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntitySection.class, priority = Integer.MAX_VALUE)
public class EntitySectionMixin<T> {

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void lal$blockRemove(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (entity instanceof Player) return;
            if (entity instanceof Entity) {
                Entity e = (Entity) entity;
                if (CombatRegistry.isInImmortalSet(e)) {
                    cir.setReturnValue(false);
                }
            }
        } catch (Throwable ignored) {}
    }
}
