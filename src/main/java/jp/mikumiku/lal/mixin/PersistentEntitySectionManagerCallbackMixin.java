package jp.mikumiku.lal.mixin;

import java.util.UUID;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback", priority = Integer.MAX_VALUE)
public class PersistentEntitySectionManagerCallbackMixin<T extends EntityAccess> {

    @Shadow private T entity;

    @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
    private void lal$blockOnRemove(Entity.RemovalReason removalReason, CallbackInfo ci) {
        try {
            if (EntityMethodHooks.isBypass()) return;
            Object entityObj = this.entity;
            if (!(entityObj instanceof Entity e)) return;
            UUID uuid = e.getUUID();
            if (CombatRegistry.isInImmortalSet(uuid)) {
                ci.cancel();
                return;
            }
            if (e instanceof Player player) {
                if (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player)) {
                    ci.cancel();
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }
}
