package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.network.LALNetwork;
import jp.mikumiku.lal.network.LALPacketHandler;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, priority = Integer.MAX_VALUE)
public class ClientPacketListenerMixin {

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/game/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"), cancellable = true)
    private void lal$handleCustomPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        try {
            ResourceLocation id = packet.getIdentifier();
            if (LALNetwork.CHANNEL_ID.equals(id) || LALNetwork.CHANNEL_BACKUP_ID.equals(id) || LALNetwork.CHANNEL_TERTIARY_ID.equals(id)) {
                FriendlyByteBuf data = packet.getData();
                byte[] bytes = new byte[data.readableBytes()];
                data.readBytes(bytes);
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    try {
                        FriendlyByteBuf safeBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
                        try {
                            LALPacketHandler.handleClientPayload(safeBuf);
                        } finally {
                            safeBuf.release();
                        }
                    } catch (Throwable ignored) {}
                });
                ci.cancel();
            }
        } catch (Throwable ignored) {}
    }

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
