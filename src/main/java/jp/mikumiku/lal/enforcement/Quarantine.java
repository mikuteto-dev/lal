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

    public static void enforceQuarantine(LivingEntity entity) {
        if (!Quarantine.isQuarantined((Entity)entity)) {
            return;
        }
        try {
            entity.teleportTo(entity.getX(), -10000.0, entity.getZ());
        }
        catch (Exception exception) {
        }
        try {
            entity.setDeltaMovement(Vec3.ZERO);
            entity.hurtMarked = true;
        }
        catch (Exception exception) {
        }
        try {
            if (entity instanceof Mob) {
                Mob mob = (Mob)entity;
                mob.setNoAi(true);
                mob.setTarget(null);
                mob.goalSelector.getRunningGoals().forEach(w -> {
                    try {
                        w.stop();
                    }
                    catch (Exception exception) {
                    }
                });
                mob.targetSelector.getRunningGoals().forEach(w -> {
                    try {
                        w.stop();
                    }
                    catch (Exception exception) {
                    }
                });
            }
        }
        catch (Exception exception) {
        }
        try {
            entity.noPhysics = true;
            entity.setInvulnerable(true);
        }
        catch (Exception exception) {
        }
        try {
            entity.setInvisible(true);
            entity.setSilent(true);
        }
        catch (Exception exception) {
        }
        try {
            entity.stopRiding();
            entity.getPassengers().forEach(Entity::stopRiding);
        }
        catch (Exception exception) {
        }
        try {
            entity.setPose(Pose.DYING);
        }
        catch (Exception exception) {
        }
        try {
            entity.invulnerableTime = Integer.MAX_VALUE;
        }
        catch (Exception exception) {
        }
    }
}
