package jp.mikumiku.lal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 0x7FFFFFFF)
public abstract class LevelRendererMixin {

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void lal$renderLALEntitiesTail(PoseStack poseStack, float partialTick, long finishNanoTime,
                                            boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                            LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        try {
            jp.mikumiku.lal.entity.LALEntityRenderer.renderAll(poseStack, camera, partialTick);
        } catch (Throwable ignored) {}
    }
}
