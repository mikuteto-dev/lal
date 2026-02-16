package jp.mikumiku.lal.item;

import java.util.Optional;
import jp.mikumiku.lal.enforcement.KillEnforcer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public class LALBowItem
extends BowItem {
    private static final double HITSCAN_RANGE = 256.0;
    private static final float ARROW_SPEED = 500.0f;

    public LALBowItem() {
        super(new Item.Properties().fireResistant().stacksTo(1).durability(Integer.MAX_VALUE));
    }

    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            this.fireShot(level, player);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingTicks) {
        if (!level.isClientSide() && entity instanceof Player) {
            Player player = (Player)entity;
            if (remainingTicks % 2 == 0) {
                this.fireShot(level, player);
            }
        }
    }

    public int getUseDuration(ItemStack stack) {
        return 72000;
    }

    private void fireShot(Level level, Player player) {
        Entity entity;
        if (!(level instanceof ServerLevel)) {
            return;
        }
        ServerLevel sl = (ServerLevel)level;
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(256.0));
        AABB searchBox = player.getBoundingBox().expandTowards(lookVec.scale(256.0)).inflate(2.0);
        EntityHitResult entityHit = null;
        double closestDist = 65536.0;
        for (Entity candidate : level.getEntities((Entity)player, searchBox, e -> e instanceof LivingEntity && e.isAlive() && e.isPickable())) {
            double dist;
            AABB aabb = candidate.getBoundingBox().inflate((double)candidate.getPickRadius() + 0.5);
            Optional hitVec = aabb.clip(eyePos, endPos);
            if (!hitVec.isPresent() || !((dist = eyePos.distanceToSqr((Vec3)hitVec.get())) < closestDist)) continue;
            closestDist = dist;
            entityHit = new EntityHitResult(candidate, (Vec3)hitVec.get());
        }
        if (entityHit != null && (entity = entityHit.getEntity()) instanceof LivingEntity) {
            LivingEntity target = (LivingEntity)entity;
            KillEnforcer.forceKill(target, sl, (Entity)player);
        }
        Arrow arrow = new Arrow(level, (LivingEntity)player){

            protected void onHitEntity(EntityHitResult result) {
                LivingEntity target;
                Level level;
                Entity entity = result.getEntity();
                if (entity instanceof LivingEntity && (level = (target = (LivingEntity)entity).level()) instanceof ServerLevel) {
                    ServerLevel s = (ServerLevel)level;
                    Entity owner = this.getOwner();
                    KillEnforcer.forceKill(target, s, owner);
                }
                this.discard();
            }

            protected void onHitBlock(BlockHitResult result) {
                this.discard();
            }
        };
        arrow.shootFromRotation((Entity)player, player.getXRot(), player.getYRot(), 0.0f, 500.0f, 0.0f);
        arrow.setBaseDamage(2.147483647E9);
        arrow.setCritArrow(true);
        arrow.setPierceLevel((byte)0);
        arrow.setNoGravity(true);
        arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        level.addFreshEntity((Entity)arrow);
    }

    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeCharged) {
    }

    public boolean isFoil(ItemStack stack) {
        return true;
    }

    public AbstractArrow customArrow(AbstractArrow arrow) {
        arrow.setBaseDamage(2.147483647E9);
        return arrow;
    }
}

