package jp.mikumiku.lal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LivingEntityRenderer.class, priority = 0x7FFFFFFF)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void lal$resetDeathAnimBeforeRender(LivingEntity entity, float entityYaw, float partialTick,
                                                 PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                                 CallbackInfo ci) {
        try {
            boolean isProtected = CombatRegistry.isInImmortalSet((Entity) entity);
            if (!isProtected && entity instanceof Player) {
                isProtected = LALSwordItem.hasLALEquipment((Player) entity)
                        && !CombatRegistry.isInKillSet(entity.getUUID());
            }
            if (isProtected) {
                entity.deathTime = 0;
                entity.hurtTime = 0;
            }
        } catch (Throwable ignored) {}
    }
}
