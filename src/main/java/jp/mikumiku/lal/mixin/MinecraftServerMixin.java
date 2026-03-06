package jp.mikumiku.lal.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MinecraftServer.class, priority = 0x7FFFFFFF)
public class MinecraftServerMixin {

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void lal$onStopServer(CallbackInfo ci) {
        try {
            jp.mikumiku.lal.transformer.EntityMethodHooks.onServerStopping(this);
        } catch (Throwable ignored) {}
    }
}
