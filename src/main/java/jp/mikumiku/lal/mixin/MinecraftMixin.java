package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void lal$blockDeathScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof DeathScreen) {
            Minecraft mc = (Minecraft)(Object)this;
            LocalPlayer player = mc.player;
            if (player != null && shouldPlayerBeAlive(player)) {
                ci.cancel();
            }
        }
    }

    private static boolean shouldPlayerBeAlive(LocalPlayer player) {
        try {
            if (LALSwordItem.hasLALEquipment((Player) player)) return true;
            if (CombatRegistry.isInImmortalSet(player.getUUID())) return true;
        } catch (Exception ignored) {}
        return false;
    }
}
