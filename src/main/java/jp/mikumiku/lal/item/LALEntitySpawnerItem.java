package jp.mikumiku.lal.item;

import jp.mikumiku.lal.entity.LALEntityBody;
import jp.mikumiku.lal.entity.LALEntityManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class LALEntitySpawnerItem extends Item {

    public LALEntitySpawnerItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isCreative()) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        try {
            LALEntityManager.removeAllPermanent();
            Vec3 eyePos = player.getEyePosition();
            Vec3 lookVec = player.getViewVector(1.0f);
            Vec3 endPos = eyePos.add(lookVec.scale(50.0));
            BlockHitResult hitResult = level.clip(new ClipContext(
                    eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            Vec3 spawnPos;
            if (hitResult.getType() == HitResult.Type.BLOCK) {
                spawnPos = new Vec3(hitResult.getBlockPos().getX() + 0.5,
                        hitResult.getBlockPos().getY() + 1.0,
                        hitResult.getBlockPos().getZ() + 0.5);
            } else {
                spawnPos = eyePos.add(lookVec.scale(5.0));
            }
            LALEntityManager.resetSuppressRecovery();
            new LALEntityBody(spawnPos, (ServerLevel) level);
            player.sendSystemMessage(Component.literal("[LAL] Entity spawned"));
        } catch (Throwable ignored) {}
        return InteractionResultHolder.success(stack);
    }
}
