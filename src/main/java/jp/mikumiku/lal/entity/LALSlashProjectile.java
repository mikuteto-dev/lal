package jp.mikumiku.lal.entity;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class LALSlashProjectile extends AbstractArrow {

    private int lifeTick = 0;

    public LALSlashProjectile(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
        this.setBaseDamage(Integer.MAX_VALUE);
        this.setNoGravity(true);
        this.setSilent(true);
    }

    public LALSlashProjectile(EntityType<? extends AbstractArrow> type, LivingEntity shooter, Level level) {
        super(type, shooter, level);
        this.setBaseDamage(Integer.MAX_VALUE);
        this.setNoGravity(true);
        this.setSilent(true);
    }

    @Override
    public void tick() {
        super.tick();
        lifeTick++;
        if (lifeTick >= 30) {
            discard();
            return;
        }
        if (!level().isClientSide() && level() instanceof ServerLevel sl) {
            spawnTrailParticles(sl);
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity hit = result.getEntity();
        if (!(hit instanceof LivingEntity living)) return;
        if (living.getClass().getName().startsWith("jp.mikumiku.lal")) return;
        if (living instanceof Player player && (player.isCreative() || player.isSpectator())) return;
        if (CombatRegistry.isInImmortalSet(living.getUUID())) return;
        if (level() instanceof ServerLevel sl) {
            applyAreaKill(sl);
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (level() instanceof ServerLevel sl) {
            Vec3 hitPos = result.getLocation();
            spawnImpactParticles(sl, hitPos);
            applyAreaKill(sl);
        }
        discard();
    }

    private void applyAreaKill(ServerLevel sl) {
        Vec3 pos = position();
        spawnImpactParticles(sl, pos);
        sl.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2.0F, 1.2F);
        AABB box = new AABB(pos.x - 5, pos.y - 5, pos.z - 5, pos.x + 5, pos.y + 5, pos.z + 5);
        try {
            List<LivingEntity> entities = sl.getEntitiesOfClass(LivingEntity.class, box);
            for (LivingEntity entity : entities) {
                try {
                    if (entity.getClass().getName().startsWith("jp.mikumiku.lal")) continue;
                    if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) continue;
                    if (CombatRegistry.isInImmortalSet(entity.getUUID())) continue;
                    if (entity instanceof Player player) {
                        try {
                            if (!CombatRegistry.isInKillSet(player.getUUID()) && LALSwordItem.hasLALEquipment(player)) continue;
                        } catch (Throwable ignored) {}
                    }
                    CombatRegistry.addToKillSet(entity.getUUID());
                    KillEnforcer.forceKill(entity, sl, null);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        discard();
    }

    private void spawnTrailParticles(ServerLevel sl) {
        Vec3 pos = position();
        sl.sendParticles(ParticleTypes.SWEEP_ATTACK, pos.x, pos.y + 0.5, pos.z, 2, 0.1, 0.1, 0.1, 0.0);
    }

    private void spawnImpactParticles(ServerLevel sl, Vec3 pos) {
        sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + 0.5, pos.z, 1, 0, 0, 0, 0);
        sl.sendParticles(ParticleTypes.SWEEP_ATTACK, pos.x, pos.y + 0.5, pos.z, 20, 3.0, 1.0, 3.0, 0.1);
    }

    @Override
    protected ItemStack getPickupItem() {
        return ItemStack.EMPTY;
    }
}
