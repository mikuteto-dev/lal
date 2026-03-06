package jp.mikumiku.lal.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlayerList.class, priority = 0x7FFFFFFF)
public class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void lal$onPlayerJoined(Connection connection, ServerPlayer player, CallbackInfo ci) {
        try {
            jp.mikumiku.lal.transformer.EntityMethodHooks.onPlayerJoined(this, player);
        } catch (Throwable ignored) {}
    }
}
