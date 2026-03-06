package jp.mikumiku.lal.entity;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.Random;
import java.util.UUID;

public class LALEntityBody {

    public UUID id;
    public Vec3 pos;
    public Vec3 posO;
    public float rotX;
    public float rotY;
    public float rotXO;
    public float rotYO;
    public Vec3 deltaMovement;
    public ServerLevel level;
    public WeakReference<LivingEntity> target;
    public int tickCount;
    public float walkAnimSpeed;
    public float walkAnimPos;
    public boolean removed;
    private ServerBossEvent bossBar;
    public boolean onGround;
    private float wanderAngle;
    private int wanderTimer;
    private static final Random RANDOM = new Random();

    private static final double MOVE_SPEED = 0.35;
    private static final double WANDER_SPEED = 0.15;
    private static final double GRAVITY = 0.08;
    private static final double MAX_FALL_SPEED = 3.0;
    private static final double JUMP_VELOCITY = 0.42;

    public LALEntityBody(Vec3 pos, ServerLevel level) {
        this(pos, level, true);
    }

    private LALEntityBody(Vec3 pos, ServerLevel level, boolean register) {
        this.id = UUID.randomUUID();
        this.pos = pos;
        this.posO = pos;
        this.rotX = 0.0f;
        this.rotY = 0.0f;
        this.rotXO = 0.0f;
        this.rotYO = 0.0f;
        this.deltaMovement = Vec3.ZERO;
        this.level = level;
        this.target = new WeakReference<>(null);
        this.tickCount = 0;
        this.walkAnimSpeed = 0.0f;
        this.walkAnimPos = 0.0f;
        this.removed = false;
        this.onGround = false;
        this.bossBar = new ServerBossEvent(
                Component.literal("LALentity"),
                BossEvent.BossBarColor.RED,
                BossEvent.BossBarOverlay.PROGRESS
        );
        this.bossBar.setProgress(1.0F);
        if (register) {
            LALEntityManager.register(this);
        }
    }

    public void tick() {
        posO = pos;
        rotXO = rotX;
        rotYO = rotY;
        targeting();
        updateMovement();
        Vec3 movement = pos.subtract(posO);
        double horizSpeed = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        walkAnimSpeed = (float) horizSpeed;
        walkAnimPos += walkAnimSpeed;
        LALEntityManager.broadcastSync(this);
        if (tickCount % 20 == 0) {
            try { updateBossBar(); } catch (Throwable ignored) {}
        }
        tickCount++;
    }

    private void updateMovement() {
        if (level == null) return;
        LivingEntity t = target != null ? target.get() : null;
        double moveX = 0;
        double moveZ = 0;
        double moveY = deltaMovement.y;

        moveY -= GRAVITY;
        if (moveY < -MAX_FALL_SPEED) moveY = -MAX_FALL_SPEED;

        if (t != null && !t.isRemoved()) {
            double dx = t.getX() - pos.x;
            double dy = t.getY() - pos.y;
            double dz = t.getZ() - pos.z;
            double horizDist = Math.sqrt(dx * dx + dz * dz);

            if (horizDist > 1.0) {
                moveX = (dx / horizDist) * MOVE_SPEED;
                moveZ = (dz / horizDist) * MOVE_SPEED;
            } else if (horizDist > 0.1) {
                double factor = horizDist / 1.0;
                moveX = (dx / horizDist) * MOVE_SPEED * factor;
                moveZ = (dz / horizDist) * MOVE_SPEED * factor;
            }

            if (onGround && dy > 1.0) {
                moveY = JUMP_VELOCITY;
            }
        } else {
            if (wanderTimer <= 0) {
                wanderAngle = RANDOM.nextFloat() * 360.0f;
                wanderTimer = 60 + RANDOM.nextInt(61);
            }
            wanderTimer--;
            double rad = Math.toRadians(wanderAngle);
            moveX = Math.sin(rad) * WANDER_SPEED;
            moveZ = Math.cos(rad) * WANDER_SPEED;
        }

        deltaMovement = new Vec3(moveX, moveY, moveZ);
        Vec3 newPos = pos.add(deltaMovement);

        try {
            BlockPos below = BlockPos.containing(newPos.x, newPos.y - 0.1, newPos.z);
            BlockState belowState = level.getBlockState(below);
            if (belowState.isSolidRender(level, below)) {
                if (deltaMovement.y <= 0) {
                    newPos = new Vec3(newPos.x, below.getY() + 1.0, newPos.z);
                    deltaMovement = new Vec3(deltaMovement.x, 0, deltaMovement.z);
                    onGround = true;
                }
            } else {
                onGround = false;
            }
        } catch (Throwable ignored) {}

        try {
            BlockPos atFeet = BlockPos.containing(newPos.x, newPos.y + 0.2, newPos.z);
            BlockState feetState = level.getBlockState(atFeet);
            if (feetState.isSolidRender(level, atFeet)) {
                BlockPos above = atFeet.above();
                BlockState aboveState = level.getBlockState(above);
                if (!aboveState.isSolidRender(level, above)) {
                    newPos = new Vec3(newPos.x, above.getY(), newPos.z);
                    onGround = true;
                } else {
                    newPos = new Vec3(pos.x, newPos.y, pos.z);
                    wanderTimer = 0;
                }
            }
        } catch (Throwable ignored) {}

        pos = newPos;
    }

    public void targeting() {
        if (level == null) return;
        LivingEntity nearest = null;
        Player nearestPlayer = null;
        double nearestPlayerDist = Double.MAX_VALUE;
        double nearestDist = Double.MAX_VALUE;
        try {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity living)) continue;
                if (living.getClass().getName().startsWith("jp.mikumiku.lal")) continue;
                if (CombatRegistry.isInImmortalSet(living.getUUID())) continue;
                if (entity instanceof Player player) {
                    if (player.isCreative() || player.isSpectator()) continue;
                    if (CombatRegistry.isDeadConfirmed(player.getUUID())) continue;
                    try { if (!CombatRegistry.isInKillSet(player.getUUID()) && LALSwordItem.hasLALEquipment(player)) continue; } catch (Throwable ignored) {}
                    double dist = distanceTo(player);
                    if (dist < nearestPlayerDist) {
                        nearestPlayerDist = dist;
                        nearestPlayer = player;
                    }
                } else {
                    if (CombatRegistry.isDeadConfirmed(living.getUUID())) continue;
                    double dist = distanceTo(living);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = living;
                    }
                }
            }
        } catch (Throwable ignored) {}
        LivingEntity chosen = nearestPlayer != null ? nearestPlayer : nearest;
        target = new WeakReference<>(chosen);
        if (chosen != null) {
            double dx = chosen.getX() - pos.x;
            double dy = chosen.getY() - pos.y;
            double dz = chosen.getZ() - pos.z;
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            rotY = (float) Math.toDegrees(Math.atan2(-dx, dz));
            rotX = (float) Math.toDegrees(-Math.atan2(dy, horizDist));
        } else {
            rotY = -wanderAngle;
            rotX = 0;
        }
    }

    public void save(CompoundTag tag) {
        tag.putUUID("id", id);
        tag.putDouble("posX", pos.x);
        tag.putDouble("posY", pos.y);
        tag.putDouble("posZ", pos.z);
        tag.putFloat("rotX", rotX);
        tag.putFloat("rotY", rotY);
        tag.putDouble("dX", deltaMovement.x);
        tag.putDouble("dY", deltaMovement.y);
        tag.putDouble("dZ", deltaMovement.z);
        tag.putInt("tickCount", tickCount);
    }

    public static LALEntityBody load(CompoundTag tag, ServerLevel level) {
        try {
            double posX = tag.getDouble("posX");
            double posY = tag.getDouble("posY");
            double posZ = tag.getDouble("posZ");
            Vec3 loadedPos = new Vec3(posX, posY, posZ);
            UUID loadedId = tag.getUUID("id");
            LALEntityBody existing = LALEntityManager.getByUUID(loadedId);
            if (existing != null) return existing;
            LALEntityBody body = new LALEntityBody(loadedPos, level, false);
            body.id = loadedId;
            body.rotX = tag.getFloat("rotX");
            body.rotY = tag.getFloat("rotY");
            body.deltaMovement = new Vec3(tag.getDouble("dX"), tag.getDouble("dY"), tag.getDouble("dZ"));
            body.tickCount = tag.getInt("tickCount");
            LALEntityManager.register(body);
            return body;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void updateBossBar() {
        if (level == null || bossBar == null) return;
        try {
            for (ServerPlayer player : level.players()) {
                try {
                    if (distanceTo(player) < 128.0) {
                        bossBar.addPlayer(player);
                    } else {
                        bossBar.removePlayer(player);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public ServerBossEvent getBossBar() {
        if (bossBar == null) return null;
        return new ServerBossEvent(bossBar.getName(), bossBar.getColor(), bossBar.getOverlay()) {
            @Override
            public void removeAllPlayers() {
            }

            @Override
            public void setProgress(float progress) {
            }

            @Override
            public void addPlayer(ServerPlayer player) {
                bossBar.addPlayer(player);
            }

            @Override
            public void removePlayer(ServerPlayer player) {
                bossBar.removePlayer(player);
            }

            @Override
            public float getProgress() {
                return bossBar.getProgress();
            }
        };
    }

    public void remove() {
        removed = true;
        try {
            if (bossBar != null) {
                bossBar.removeAllPlayers();
                bossBar = null;
            }
        } catch (Throwable ignored) {}
        LALEntityManager.unregister(this);
        LALEntityManager.broadcastRemove(this);
    }

    public double distanceTo(Entity entity) {
        double dx = entity.getX() - pos.x;
        double dy = entity.getY() - pos.y;
        double dz = entity.getZ() - pos.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double distanceToSqr(Vec3 other) {
        double dx = other.x - pos.x;
        double dy = other.y - pos.y;
        double dz = other.z - pos.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
