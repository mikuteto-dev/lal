package jp.mikumiku.lal.enforcement;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

public class Quarantine {
    private static final Set<UUID> QUARANTINED = ConcurrentHashMap.newKeySet();
    private static final double QUARANTINE_Y = -10000.0;

    public Quarantine() {
        super();
    }

    public static void addToQuarantine(UUID uuid) {
        QUARANTINED.add(uuid);
    }

    public static void removeFromQuarantine(UUID uuid) {
        QUARANTINED.remove(uuid);
    }

    public static boolean isQuarantined(UUID uuid) {
        return QUARANTINED.contains(uuid);
    }

    public static boolean isQuarantined(Entity entity) {
        return entity != null && QUARANTINED.contains(entity.getUUID());
    }

    public static Set<UUID> getQuarantinedSet() {
        return QUARANTINED;
    }

    private static void safeRun(Runnable action) {
        try { action.run(); } catch (Exception ignored) {}
    }

    public static void enforceQuarantine(LivingEntity entity) {
        if (!isQuarantined((Entity) entity)) return;
        safeRun(() -> entity.teleportTo(entity.getX(), QUARANTINE_Y, entity.getZ()));
        safeRun(() -> { entity.setDeltaMovement(Vec3.ZERO); entity.hurtMarked = true; });
        safeRun(() -> {
            if (entity instanceof Mob) {
                Mob mob = (Mob) entity;
                mob.setNoAi(true);
                mob.setTarget(null);
                mob.goalSelector.getRunningGoals().forEach(w -> safeRun(w::stop));
                mob.targetSelector.getRunningGoals().forEach(w -> safeRun(w::stop));
            }
        });
        safeRun(() -> { entity.noPhysics = true; entity.setInvulnerable(true); });
        safeRun(() -> { entity.setInvisible(true); entity.setSilent(true); });
        safeRun(() -> { entity.stopRiding(); entity.getPassengers().forEach(Entity::stopRiding); });
        safeRun(() -> entity.setPose(Pose.DYING));
        safeRun(() -> entity.invulnerableTime = Integer.MAX_VALUE);
    }
}
