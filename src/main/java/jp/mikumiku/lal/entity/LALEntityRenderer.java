package jp.mikumiku.lal.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LALEntityRenderer {

    private static final ResourceLocation CAT_TEXTURE = new ResourceLocation("textures/entity/cat/all_black.png");
    private static final ResourceLocation WITHER_ARMOR = new ResourceLocation("textures/entity/wither/wither_armor.png");

    private static Map<UUID, LALEntityClientState> clientStates = LALEntityClientStateStore.states;

    private static ModelPart catRoot = null;
    private static ModelPart head = null;
    private static ModelPart body = null;
    private static ModelPart tail1 = null;
    private static ModelPart tail2 = null;
    private static ModelPart leftHindLeg = null;
    private static ModelPart rightHindLeg = null;
    private static ModelPart leftFrontLeg = null;
    private static ModelPart rightFrontLeg = null;
    private static boolean modelInitAttempted = false;

    private static final List<PendingEffect> pendingEffects = new ArrayList<>();
    private static volatile int clientTickCount = 0;
    private static volatile long lastRenderNano = 0;
    private static final long MIN_RENDER_INTERVAL = 4_000_000L;

    private static class PendingEffect {
        int type;
        double x, y, z, tx, ty, tz;
        int life;

        PendingEffect(int type, double x, double y, double z, double tx, double ty, double tz) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
            this.life = 10;
        }
    }

    private static void ensureModel() {
        if (modelInitAttempted && catRoot != null) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            EntityModelSet modelSet = mc.getEntityModels();
            if (modelSet == null) return;
            catRoot = modelSet.bakeLayer(ModelLayers.CAT);
            head = catRoot.getChild("head");
            body = catRoot.getChild("body");
            tail1 = catRoot.getChild("tail1");
            tail2 = catRoot.getChild("tail2");
            leftHindLeg = catRoot.getChild("left_hind_leg");
            rightHindLeg = catRoot.getChild("right_hind_leg");
            leftFrontLeg = catRoot.getChild("left_front_leg");
            rightFrontLeg = catRoot.getChild("right_front_leg");
            modelInitAttempted = true;
        } catch (Throwable ignored) {}
    }

    private static void drainSharedEffects() {
        double[] effect;
        while ((effect = LALEntityClientStateStore.pendingEffects.poll()) != null) {
            try {
                pendingEffects.add(new PendingEffect((int) effect[0], effect[1], effect[2], effect[3], effect[4], effect[5], effect[6]));
            } catch (Throwable ignored) {}
        }
    }

    public static void renderAll(PoseStack poseStack, Camera camera, float partialTick) {
        try {
            long now = System.nanoTime();
            if (now - lastRenderNano < MIN_RENDER_INTERVAL) return;
            lastRenderNano = now;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return;
            ensureModel();
            if (catRoot == null) return;
            clientTickCount++;
        } catch (Throwable ignored) { return; }

        try { LALEntityClientStateStore.verifyIntegrity(); } catch (Throwable ignored) {}
        try { drainSharedEffects(); } catch (Throwable ignored) {}

        try {
            for (LALEntityClientState state : clientStates.values()) {
                try {
                    renderEntity(poseStack, state, camera, partialTick);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        try {
            List<PendingEffect> toRemove = new ArrayList<>();
            for (PendingEffect effect : pendingEffects) {
                effect.life--;
                if (effect.life <= 0) {
                    toRemove.add(effect);
                }
            }
            pendingEffects.removeAll(toRemove);
        } catch (Throwable ignored) {}
    }

    private static void renderEntity(PoseStack poseStack, LALEntityClientState state, Camera camera, float partialTick) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return;

            Vec3 camPos = camera.getPosition();
            Vec3 entityPos = state.getInterpolatedPos(partialTick);

            double dx = entityPos.x - camPos.x;
            double dy = entityPos.y - camPos.y;
            double dz = entityPos.z - camPos.z;

            if (dx * dx + dy * dy + dz * dz > 16384.0) return;

            float tickFloat = clientTickCount + partialTick;
            int attackMode = state.attackMode;
            int attackTimer = state.attackTimer;

            poseStack.pushPose();
            poseStack.translate(dx, dy, dz);

            float bodyYaw = state.getInterpolatedRotY(partialTick);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));

            float progress = 0.0f;
            if (attackMode != 0 && attackTimer > 0) {
                float maxTimer = switch (attackMode) {
                    case 1 -> (float) LALEntityAttacks.SWIPE_DURATION;
                    case 2 -> (float) LALEntityAttacks.ERASE_DURATION;
                    case 3 -> (float) LALEntityAttacks.BEAM_DURATION;
                    case 4 -> (float) LALEntityAttacks.TOTALITY_DURATION;
                    case 5 -> (float) LALEntityAttacks.TELEPORT_SLASH_DURATION;
                    case 6 -> (float) LALEntityAttacks.GRAVITY_FIELD_DURATION;
                    case 7 -> (float) LALEntityAttacks.SHADOW_CLONE_DURATION;
                    case 8 -> (float) LALEntityAttacks.DEATH_ZONE_DURATION;
                    case 9 -> (float) LALEntityAttacks.WEAKNESS_AURA_DURATION;
                    case 10 -> (float) LALEntityAttacks.HOMING_BEAM_DURATION;
                    case 11 -> (float) LALEntityAttacks.CHAIN_KILL_DURATION;
                    default -> 1.0f;
                };
                progress = Mth.clamp(1.0f - (attackTimer / maxTimer), 0.0f, 1.0f);
            }

            applyBodyTransform(poseStack, attackMode, progress, tickFloat);

            poseStack.scale(-1.0F, -1.0F, 1.0F);
            poseStack.translate(0.0D, -1.501D, 0.0D);

            float limbSwing = state.walkAnimPos;
            float limbSwingAmount = Math.min(state.walkAnimSpeed * 4.0F, 1.0F);

            rightFrontLeg.yRot = 0.0f;
            leftFrontLeg.yRot = 0.0f;
            rightHindLeg.yRot = 0.0f;
            leftHindLeg.yRot = 0.0f;

            applyLegAnimation(attackMode, progress, tickFloat, limbSwing, limbSwingAmount);
            applyHeadAnimation(state, attackMode, progress, tickFloat, partialTick);
            applyTailAnimation(attackMode, progress, tickFloat);

            int overlay = attackMode != 0 ? OverlayTexture.pack(0.0F, true) : OverlayTexture.NO_OVERLAY;

            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

            int light;
            try {
                light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(entityPos));
            } catch (Throwable ignored) {
                light = 0xF000F0;
            }

            VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(CAT_TEXTURE));
            catRoot.render(poseStack, vertexConsumer, light, overlay);

            if (attackMode == 4 && progress > 0.2f && progress < 0.8f) {
                float glowAlpha = Mth.sin((progress - 0.2f) / 0.6f * (float) Math.PI) * 0.6f;
                VertexConsumer glowConsumer = bufferSource.getBuffer(RenderType.entityTranslucent(CAT_TEXTURE));
                catRoot.render(poseStack, glowConsumer, 0xF000F0, OverlayTexture.NO_OVERLAY, 1.0F, 0.3F, 0.3F, glowAlpha);
            } else if (attackMode == 7) {
                float ghostAlpha = 0.45f + Mth.sin(tickFloat * 0.4f) * 0.1f;
                VertexConsumer ghostConsumer = bufferSource.getBuffer(RenderType.entityTranslucent(CAT_TEXTURE));
                catRoot.render(poseStack, ghostConsumer, light, OverlayTexture.NO_OVERLAY, 0.7F, 0.7F, 1.0F, ghostAlpha);
            } else if (attackMode == 9) {
                float auraAlpha = Mth.sin(tickFloat * 0.8f) * 0.3f + 0.3f;
                VertexConsumer auraConsumer = bufferSource.getBuffer(RenderType.entityTranslucent(CAT_TEXTURE));
                catRoot.render(poseStack, auraConsumer, 0xF000F0, OverlayTexture.NO_OVERLAY, 0.3F, 1.0F, 0.5F, auraAlpha);
            } else if (attackMode == 11) {
                float sparkAlpha = Mth.sin(tickFloat * 6.0f) * 0.4f + 0.2f;
                VertexConsumer sparkConsumer = bufferSource.getBuffer(RenderType.entityTranslucent(CAT_TEXTURE));
                catRoot.render(poseStack, sparkConsumer, 0xF000F0, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 0.2F, sparkAlpha);
            }

            float uvSpeed = tickFloat * 0.01F;
            float uOffset = uvSpeed % 1.0F;
            float vOffset = (uvSpeed * 0.5F) % 1.0F;
            VertexConsumer armorConsumer = bufferSource.getBuffer(RenderType.energySwirl(WITHER_ARMOR, uOffset, vOffset));
            catRoot.render(poseStack, armorConsumer, 0xF000F0, OverlayTexture.NO_OVERLAY, 0.2F, 0.4F, 1.0F, 0.6F);

            bufferSource.endBatch();

            poseStack.popPose();
        } catch (Throwable ignored) {}
    }

    private static void applyBodyTransform(PoseStack poseStack, int attackMode, float progress, float tickFloat) {
        if (attackMode == 1) {
            float lunge;
            if (progress < 0.3f) {
                lunge = progress / 0.3f;
            } else {
                lunge = 1.0f - (progress - 0.3f) / 0.7f;
            }
            poseStack.mulPose(Axis.XP.rotationDegrees(lunge * 35.0f));
            float sideRock = Mth.sin(progress * (float) Math.PI * 3) * 5.0f * (1.0f - progress);
            poseStack.mulPose(Axis.ZP.rotationDegrees(sideRock));
        } else if (attackMode == 2) {
            float rise;
            if (progress < 0.2f) {
                rise = progress / 0.2f;
            } else if (progress < 0.7f) {
                rise = 1.0f;
            } else {
                rise = 1.0f - (progress - 0.7f) / 0.3f;
            }
            float bob = Mth.sin(tickFloat * 0.4f) * 0.1f * rise;
            poseStack.translate(0.0D, rise * 0.6D + bob, 0.0D);
            if (rise > 0.5f) {
                poseStack.mulPose(Axis.YP.rotationDegrees(tickFloat * 4.0f * rise));
            }
        } else if (attackMode == 3) {
            float angle;
            if (progress < 0.15f) {
                angle = -(progress / 0.15f) * 15.0f;
            } else if (progress < 0.3f) {
                float t = (progress - 0.15f) / 0.15f;
                angle = Mth.lerp(t, -15.0f, 25.0f);
            } else {
                float hold = 25.0f * (1.0f - (progress - 0.3f) / 0.7f);
                float vibrate = Mth.sin(tickFloat * 2.5f) * 2.0f * (1.0f - progress);
                angle = hold + vibrate;
            }
            poseStack.mulPose(Axis.XP.rotationDegrees(angle));
        } else if (attackMode == 4) {
            float rise;
            if (progress < 0.25f) {
                rise = progress / 0.25f;
            } else if (progress < 0.7f) {
                float wave = Mth.sin((progress - 0.25f) / 0.45f * (float) Math.PI) * 0.3f;
                rise = 1.0f + wave;
            } else {
                rise = 1.0f - (progress - 0.7f) / 0.3f;
            }
            poseStack.translate(0.0D, rise * 0.8D, 0.0D);
            float spinSpeed;
            if (progress < 0.25f) {
                spinSpeed = progress / 0.25f * 5.0f;
            } else if (progress < 0.7f) {
                spinSpeed = 5.0f + (progress - 0.25f) / 0.45f * 15.0f;
            } else {
                spinSpeed = Mth.lerp((progress - 0.7f) / 0.3f, 20.0f, 2.0f);
            }
            poseStack.mulPose(Axis.YP.rotationDegrees(tickFloat * spinSpeed));
            if (progress > 0.35f && progress < 0.65f) {
                float pulse = 1.0f + Mth.sin((progress - 0.35f) / 0.3f * (float) Math.PI) * 0.15f;
                poseStack.scale(pulse, pulse, pulse);
            }
        } else if (attackMode == 5) {
            float warpIn = progress < 0.3f ? progress / 0.3f : 1.0f - (progress - 0.3f) / 0.7f;
            poseStack.scale(1.0f + warpIn * 0.3f, 1.0f, 1.0f + warpIn * 0.3f);
            poseStack.mulPose(Axis.YP.rotationDegrees(warpIn * 180.0f * (progress < 0.3f ? 1.0f : -1.0f)));
            float lunge = progress < 0.3f ? 0.0f : Mth.sin((progress - 0.3f) / 0.7f * (float) Math.PI) * 35.0f;
            poseStack.mulPose(Axis.XP.rotationDegrees(lunge));
        } else if (attackMode == 6) {
            float rise = progress < 0.2f ? progress / 0.2f : progress > 0.8f ? 1.0f - (progress - 0.8f) / 0.2f : 1.0f;
            poseStack.translate(0.0D, rise * 0.8D, 0.0D);
            float bob = Mth.sin(tickFloat * 0.5f) * 0.06f * rise;
            poseStack.translate(0.0D, bob, 0.0D);
        } else if (attackMode == 7) {
            float pulse = 1.0f + Mth.sin(tickFloat * 0.4f) * 0.05f;
            poseStack.scale(pulse, pulse, pulse);
            float sway = Mth.sin(tickFloat * 0.15f) * 3.0f;
            poseStack.mulPose(Axis.YP.rotationDegrees(sway));
        } else if (attackMode == 8) {
            float curl = progress < 0.3f ? progress / 0.3f : progress > 0.7f ? 1.0f - (progress - 0.7f) / 0.3f : 1.0f;
            float rise = curl * 0.4f;
            poseStack.translate(0.0D, rise, 0.0D);
            poseStack.scale(1.0f + curl * 0.2f, 1.0f - curl * 0.15f, 1.0f + curl * 0.2f);
            poseStack.mulPose(Axis.YP.rotationDegrees(tickFloat * 3.0f * curl));
        } else if (attackMode == 9) {
            float sit = progress < 0.2f ? progress / 0.2f : 1.0f;
            poseStack.translate(0.0D, -sit * 0.3D, 0.0D);
            float pulse = 1.0f + Mth.sin(tickFloat * 0.8f) * 0.08f * sit;
            poseStack.scale(pulse, pulse, pulse);
        } else if (attackMode == 10) {
            float angle = Mth.sin(tickFloat * 0.3f) * 8.0f * (1.0f - progress);
            poseStack.mulPose(Axis.XP.rotationDegrees(angle));
            float bob = Mth.sin(tickFloat * 0.5f) * 0.05f;
            poseStack.translate(0.0D, bob, 0.0D);
        } else if (attackMode == 11) {
            float vibX = Mth.sin(tickFloat * 4.5f) * 0.04f * (1.0f - progress);
            float vibZ = Mth.cos(tickFloat * 4.5f) * 0.04f * (1.0f - progress);
            poseStack.translate(vibX, 0.0D, vibZ);
            float spark = 1.0f + Mth.sin(tickFloat * 6.0f) * 0.03f;
            poseStack.scale(spark, spark, spark);
        } else {
            float breathe = Mth.sin(tickFloat * 0.1f) * 0.015f;
            poseStack.translate(0.0D, breathe, 0.0D);
        }
    }

    private static void applyLegAnimation(int attackMode, float progress, float tickFloat, float limbSwing, float limbSwingAmount) {
        if (attackMode == 1) {
            float pawSwing;
            if (progress < 0.25f) {
                pawSwing = -(progress / 0.25f) * 2.0f;
            } else if (progress < 0.5f) {
                float t = (progress - 0.25f) / 0.25f;
                pawSwing = Mth.lerp(t, -2.0f, 0.8f);
            } else {
                pawSwing = Mth.lerp((progress - 0.5f) / 0.5f, 0.8f, 0.0f);
            }
            rightFrontLeg.xRot = pawSwing;
            leftFrontLeg.xRot = pawSwing;
            rightHindLeg.xRot = -pawSwing * 0.4f;
            leftHindLeg.xRot = -pawSwing * 0.4f;
            float pawSplay = Mth.sin(progress * (float) Math.PI) * 0.3f;
            rightFrontLeg.yRot = pawSplay;
            leftFrontLeg.yRot = -pawSplay;
        } else if (attackMode == 2) {
            float rise = progress < 0.2f ? progress / 0.2f : progress > 0.7f ? 1.0f - (progress - 0.7f) / 0.3f : 1.0f;
            float dangle = Mth.sin(tickFloat * 0.4f) * 0.25f * rise;
            rightFrontLeg.xRot = 0.6f * rise + dangle;
            leftFrontLeg.xRot = 0.6f * rise - dangle;
            rightHindLeg.xRot = 0.6f * rise - dangle;
            leftHindLeg.xRot = 0.6f * rise + dangle;
        } else if (attackMode == 3) {
            float brace;
            if (progress < 0.15f) {
                brace = 0.0f;
            } else if (progress < 0.3f) {
                brace = (progress - 0.15f) / 0.15f;
            } else {
                brace = 1.0f - (progress - 0.3f) / 0.7f;
            }
            rightFrontLeg.xRot = -0.9f * brace;
            leftFrontLeg.xRot = -0.9f * brace;
            rightHindLeg.xRot = 0.7f * brace;
            leftHindLeg.xRot = 0.7f * brace;
            float vibrate = Mth.sin(tickFloat * 3.0f) * 0.05f * brace;
            rightFrontLeg.xRot += vibrate;
            leftFrontLeg.xRot -= vibrate;
        } else if (attackMode == 4) {
            float rise = progress < 0.25f ? progress / 0.25f : progress > 0.7f ? 1.0f - (progress - 0.7f) / 0.3f : 1.0f;
            float spread = Mth.sin(tickFloat * 0.8f) * 0.4f * rise;
            rightFrontLeg.xRot = -1.0f * rise + spread;
            leftFrontLeg.xRot = -1.0f * rise - spread;
            rightHindLeg.xRot = 1.0f * rise - spread;
            leftHindLeg.xRot = 1.0f * rise + spread;
            rightFrontLeg.yRot = 0.3f * rise;
            leftFrontLeg.yRot = -0.3f * rise;
        } else if (attackMode == 5) {
            float warpIn = progress < 0.3f ? progress / 0.3f : 1.0f - (progress - 0.3f) / 0.7f;
            float pawSwing = Mth.sin(progress * (float) Math.PI) * 1.5f;
            rightFrontLeg.xRot = -pawSwing;
            leftFrontLeg.xRot = pawSwing;
            rightFrontLeg.yRot = warpIn * 0.4f;
            leftFrontLeg.yRot = -warpIn * 0.4f;
        } else if (attackMode == 6) {
            float rise = progress < 0.2f ? progress / 0.2f : progress > 0.8f ? 1.0f - (progress - 0.8f) / 0.2f : 1.0f;
            rightFrontLeg.xRot = -1.2f * rise;
            leftFrontLeg.xRot = -1.2f * rise;
            rightFrontLeg.yRot = 0.5f * rise;
            leftFrontLeg.yRot = -0.5f * rise;
            rightHindLeg.xRot = 0.4f * rise;
            leftHindLeg.xRot = 0.4f * rise;
        } else if (attackMode == 7) {
            float sway = Mth.sin(tickFloat * 0.15f) * 0.1f;
            rightFrontLeg.xRot = sway;
            leftFrontLeg.xRot = -sway;
            rightHindLeg.xRot = -sway * 0.5f;
            leftHindLeg.xRot = sway * 0.5f;
        } else if (attackMode == 8) {
            float curl = progress < 0.3f ? progress / 0.3f : progress > 0.7f ? 1.0f - (progress - 0.7f) / 0.3f : 1.0f;
            rightFrontLeg.xRot = 0.8f * curl;
            leftFrontLeg.xRot = 0.8f * curl;
            rightHindLeg.xRot = -0.8f * curl;
            leftHindLeg.xRot = -0.8f * curl;
        } else if (attackMode == 9) {
            float sit = progress < 0.2f ? progress / 0.2f : 1.0f;
            rightHindLeg.xRot = 1.2f * sit;
            leftHindLeg.xRot = 1.2f * sit;
            rightFrontLeg.xRot = 0.3f * sit;
            leftFrontLeg.xRot = 0.3f * sit;
        } else if (attackMode == 10) {
            float track = Mth.sin(tickFloat * 0.3f) * 0.2f;
            rightFrontLeg.xRot = track;
            leftFrontLeg.xRot = -track;
            rightHindLeg.xRot = -track * 0.5f;
            leftHindLeg.xRot = track * 0.5f;
        } else if (attackMode == 11) {
            float vib = Mth.sin(tickFloat * 5.0f) * 0.15f * (1.0f - progress);
            rightFrontLeg.xRot = vib;
            leftFrontLeg.xRot = -vib;
            rightHindLeg.xRot = -vib * 0.8f;
            leftHindLeg.xRot = vib * 0.8f;
        } else {
            if (limbSwingAmount < 0.01f) {
                float idle = Mth.sin(tickFloat * 0.05f) * 0.05f;
                rightHindLeg.xRot = idle;
                leftHindLeg.xRot = -idle;
                rightFrontLeg.xRot = -idle;
                leftFrontLeg.xRot = idle;
            } else {
                float legAnim = Mth.cos(limbSwing * 0.6662F) * limbSwingAmount;
                rightHindLeg.xRot = legAnim;
                leftHindLeg.xRot = -legAnim;
                rightFrontLeg.xRot = -legAnim;
                leftFrontLeg.xRot = legAnim;
            }
        }
    }

    private static void applyHeadAnimation(LALEntityClientState state, int attackMode, float progress, float tickFloat, float partialTick) {
        if (attackMode == 1) {
            float headPitch;
            if (progress < 0.3f) {
                headPitch = -(progress / 0.3f) * 0.5f;
            } else {
                headPitch = Mth.lerp((progress - 0.3f) / 0.7f, -0.5f, 0.0f);
            }
            head.xRot = headPitch;
            head.yRot = 0.0f;
        } else if (attackMode == 2) {
            float rise = progress < 0.2f ? progress / 0.2f : progress > 0.7f ? 1.0f - (progress - 0.7f) / 0.3f : 1.0f;
            head.xRot = -0.4f * rise;
            head.yRot = 0.0f;
        } else if (attackMode == 3) {
            if (progress < 0.15f) {
                head.xRot = (progress / 0.15f) * 0.3f;
            } else if (progress < 0.3f) {
                float t = (progress - 0.15f) / 0.15f;
                head.xRot = Mth.lerp(t, 0.3f, -0.5f);
            } else {
                float vibrate = Mth.sin(tickFloat * 3.0f) * 0.03f * (1.0f - progress);
                head.xRot = -0.5f * (1.0f - (progress - 0.3f) / 0.7f) + vibrate;
            }
            head.yRot = 0.0f;
        } else if (attackMode == 4) {
            float rise = progress < 0.25f ? progress / 0.25f : progress > 0.7f ? 1.0f - (progress - 0.7f) / 0.3f : 1.0f;
            head.xRot = -0.6f * rise;
            head.yRot = 0.0f;
        } else if (attackMode == 5) {
            float warpIn = progress < 0.3f ? progress / 0.3f : 1.0f - (progress - 0.3f) / 0.7f;
            head.xRot = -0.3f * warpIn;
            head.yRot = Mth.sin(tickFloat * 0.5f) * 0.2f * warpIn;
        } else if (attackMode == 6) {
            float rise = progress < 0.2f ? progress / 0.2f : progress > 0.8f ? 1.0f - (progress - 0.8f) / 0.2f : 1.0f;
            head.xRot = -0.5f * rise;
            head.yRot = Mth.sin(tickFloat * 0.3f) * 0.15f * rise;
        } else if (attackMode == 7) {
            head.xRot = state.getInterpolatedRotX(partialTick) * ((float) Math.PI / 180.0F);
            head.yRot = Mth.sin(tickFloat * 0.2f) * 0.1f;
        } else if (attackMode == 8) {
            float curl = progress < 0.3f ? progress / 0.3f : progress > 0.7f ? 1.0f - (progress - 0.7f) / 0.3f : 1.0f;
            head.xRot = 0.8f * curl;
            head.yRot = 0.0f;
        } else if (attackMode == 9) {
            float sit = progress < 0.2f ? progress / 0.2f : 1.0f;
            head.xRot = 0.6f * sit;
            float pulse = Mth.sin(tickFloat * 0.8f) * 0.05f * sit;
            head.xRot += pulse;
            head.yRot = 0.0f;
        } else if (attackMode == 10) {
            head.xRot = state.getInterpolatedRotX(partialTick) * ((float) Math.PI / 180.0F);
            head.yRot = Mth.sin(tickFloat * 0.4f) * 0.3f;
        } else if (attackMode == 11) {
            float vib = Mth.sin(tickFloat * 5.0f) * 0.08f * (1.0f - progress);
            head.xRot = vib;
            head.yRot = Mth.cos(tickFloat * 5.0f) * 0.06f * (1.0f - progress);
        } else {
            head.xRot = state.getInterpolatedRotX(partialTick) * ((float) Math.PI / 180.0F);
            head.yRot = 0.0F;
        }
    }

    private static void applyTailAnimation(int attackMode, float progress, float tickFloat) {
        if (attackMode == 1) {
            tail1.xRot = Mth.sin(progress * (float) Math.PI * 2.5f) * 0.8f;
            tail2.xRot = tail1.xRot * 1.3f;
        } else if (attackMode == 2) {
            float rise = progress < 0.2f ? progress / 0.2f : progress > 0.7f ? 1.0f - (progress - 0.7f) / 0.3f : 1.0f;
            tail1.xRot = -0.6f * rise + Mth.sin(tickFloat * 0.3f) * 0.2f * rise;
            tail2.xRot = -0.3f * rise + Mth.sin(tickFloat * 0.4f) * 0.15f * rise;
        } else if (attackMode == 3) {
            float stiff = progress > 0.15f ? Mth.clamp((progress - 0.15f) / 0.15f, 0.0f, 1.0f) : 0.0f;
            float relax = progress > 0.6f ? (progress - 0.6f) / 0.4f : 0.0f;
            float tailStiff = stiff * (1.0f - relax);
            tail1.xRot = 0.8f * tailStiff;
            tail2.xRot = 0.6f * tailStiff;
        } else if (attackMode == 4) {
            float rise = progress < 0.25f ? progress / 0.25f : progress > 0.7f ? 1.0f - (progress - 0.7f) / 0.3f : 1.0f;
            tail1.xRot = -0.8f * rise + Mth.sin(tickFloat * 0.6f) * 0.3f * rise;
            tail2.xRot = -0.5f * rise + Mth.sin(tickFloat * 0.8f) * 0.2f * rise;
        } else if (attackMode == 5) {
            float warp = progress < 0.3f ? progress / 0.3f : 1.0f - (progress - 0.3f) / 0.7f;
            tail1.xRot = Mth.sin(tickFloat * 0.8f) * 0.6f * warp;
            tail2.xRot = Mth.sin(tickFloat * 1.0f) * 0.5f * warp;
        } else if (attackMode == 6) {
            float rise = progress < 0.2f ? progress / 0.2f : progress > 0.8f ? 1.0f - (progress - 0.8f) / 0.2f : 1.0f;
            tail1.xRot = -1.0f * rise + Mth.sin(tickFloat * 0.4f) * 0.2f * rise;
            tail2.xRot = -0.7f * rise + Mth.sin(tickFloat * 0.5f) * 0.15f * rise;
        } else if (attackMode == 7) {
            tail1.xRot = 0.5f + Mth.sin(tickFloat * 0.25f) * 0.3f;
            tail2.xRot = 0.4f + Mth.sin(tickFloat * 0.35f + 0.5f) * 0.2f;
        } else if (attackMode == 8) {
            float curl = progress < 0.3f ? progress / 0.3f : progress > 0.7f ? 1.0f - (progress - 0.7f) / 0.3f : 1.0f;
            tail1.xRot = -1.2f * curl;
            tail2.xRot = -0.9f * curl;
        } else if (attackMode == 9) {
            float sit = progress < 0.2f ? progress / 0.2f : 1.0f;
            tail1.xRot = -0.5f * sit + Mth.sin(tickFloat * 0.6f) * 0.15f * sit;
            tail2.xRot = -0.3f * sit + Mth.sin(tickFloat * 0.8f) * 0.1f * sit;
        } else if (attackMode == 10) {
            tail1.xRot = 0.3f + Mth.sin(tickFloat * 0.3f) * 0.4f;
            tail2.xRot = 0.2f + Mth.sin(tickFloat * 0.4f + 0.3f) * 0.3f;
        } else if (attackMode == 11) {
            float vib = Mth.sin(tickFloat * 5.0f) * 0.2f * (1.0f - progress);
            tail1.xRot = 0.4f + vib;
            tail2.xRot = 0.3f + vib * 0.7f;
        } else {
            tail1.xRot = 0.4F + Mth.sin(tickFloat * 0.08F) * 0.2F;
            tail2.xRot = tail1.xRot * 0.6F + Mth.sin(tickFloat * 0.12F) * 0.1F;
        }
    }

    public static void renderAllFallback() {
        try {
            long now = System.nanoTime();
            if (now - lastRenderNano < MIN_RENDER_INTERVAL) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return;
            if (mc.gameRenderer == null) return;
            Camera camera = mc.gameRenderer.getMainCamera();
            if (camera == null) return;
            float partialTick = mc.getFrameTime();
            PoseStack poseStack = new PoseStack();
            poseStack.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
            poseStack.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));
            renderAll(poseStack, camera, partialTick);
        } catch (Throwable ignored) {}
    }

    public static void updateClientState(UUID id, double x, double y, double z, float rotX, float rotY, int attackMode, float walkAnimSpeed) {
        try {
            LALEntityClientStateStore.update(id, x, y, z, rotX, rotY, attackMode, walkAnimSpeed, 0);
        } catch (Throwable ignored) {}
    }

    public static void addClientState(UUID id, double x, double y, double z) {
        try {
            LALEntityClientStateStore.add(id, x, y, z);
        } catch (Throwable ignored) {}
    }

    public static void removeClientState(UUID id) {
        try {
            LALEntityClientStateStore.remove(id);
        } catch (Throwable ignored) {}
    }

    public static void playEffect(int effectType, double x, double y, double z, double tx, double ty, double tz) {
        try {
            pendingEffects.add(new PendingEffect(effectType, x, y, z, tx, ty, tz));
        } catch (Throwable ignored) {}
    }
}
