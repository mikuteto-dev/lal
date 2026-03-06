package jp.mikumiku.lal.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerPlayer.class, priority = 0x7FFFFFFF)
public class ServerPlayerMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void lal$onServerPlayerTick(CallbackInfo ci) {
        try {
            jp.mikumiku.lal.transformer.EntityMethodHooks.onServerPlayerTick(this);
        } catch (Throwable ignored) {}
    }
}
