package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, priority = Integer.MAX_VALUE)
public class ClientPacketListenerMixin {

    @Inject(method = "handlePlayerCombatKill(Lnet/minecraft/network/protocol/game/ClientboundPlayerCombatKillPacket;)V", at = @At("HEAD"), cancellable = true)
    private void lal$blockDeathKillPacket(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.player == null) return;
            net.minecraft.client.player.LocalPlayer player = mc.player;
            if (LALSwordItem.hasLALEquipment((Player) player)
                    || CombatRegistry.isInImmortalSet(player.getUUID())) {
                ci.cancel();
            }
        } catch (Throwable ignored) {}
    }
}
