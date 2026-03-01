package jp.mikumiku.lal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = ScreenEffectRenderer.class, priority = Integer.MAX_VALUE)
public class ScreenEffectRendererMixin {

    @Inject(method = "renderScreenEffect", at = @At("HEAD"), cancellable = true)
    private static void lal$blockOverlay(Minecraft mc, PoseStack poseStack, CallbackInfo ci) {
        try {
            Player player = mc.player;
            if (player == null) return;
            UUID uuid = player.getUUID();
            if (CombatRegistry.isInImmortalSet(uuid) ||
                    (!CombatRegistry.isInKillSet(uuid) && LALSwordItem.hasLALEquipment(player))) {
                ci.cancel();
            }
        } catch (Throwable ignored) {}
    }
}
