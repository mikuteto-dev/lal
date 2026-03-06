package jp.mikumiku.lal.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public class LALEntityClientState {
    public UUID id;
    public Vec3 pos;
    public Vec3 posO;
    public float rotX, rotY, rotXO, rotYO;
    public float walkAnimSpeed;
    public float walkAnimPos;
    public int attackMode;
    public int attackTimer;
    public long lastUpdateTime;

    public LALEntityClientState(UUID id, double x, double y, double z) {
        this.id = id;
        this.pos = new Vec3(x, y, z);
        this.posO = this.pos;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public void updateFromPacket(double x, double y, double z, float rotX, float rotY, int attackMode, float walkAnimSpeed, int attackTimer) {
        this.posO = this.pos;
        this.rotXO = this.rotX;
        this.rotYO = this.rotY;
        this.pos = new Vec3(x, y, z);
        this.rotX = rotX;
        this.rotY = rotY;
        this.attackMode = attackMode;
        this.walkAnimSpeed = walkAnimSpeed;
        this.walkAnimPos += walkAnimSpeed;
        this.attackTimer = attackTimer;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public Vec3 getInterpolatedPos(float partialTick) {
        return new Vec3(
            Mth.lerp(partialTick, posO.x, pos.x),
            Mth.lerp(partialTick, posO.y, pos.y),
            Mth.lerp(partialTick, posO.z, pos.z)
        );
    }

    public float getInterpolatedRotX(float partialTick) {
        return Mth.lerp(partialTick, rotXO, rotX);
    }

    public float getInterpolatedRotY(float partialTick) {
        return Mth.rotLerp(partialTick, rotYO, rotY);
    }
}
