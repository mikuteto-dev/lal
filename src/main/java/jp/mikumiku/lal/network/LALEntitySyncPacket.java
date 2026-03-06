package jp.mikumiku.lal.network;

import net.minecraft.network.FriendlyByteBuf;

import java.nio.ByteBuffer;
import java.util.UUID;

public class LALEntitySyncPacket implements LALPacket {
    private final UUID bodyId;
    private final double x;
    private final double y;
    private final double z;
    private final float rotX;
    private final float rotY;
    private final byte attackMode;
    private final float walkAnimSpeed;
    private final byte attackTimer;
    private final byte[] hmac;

    public LALEntitySyncPacket(UUID bodyId, double x, double y, double z, float rotX, float rotY, byte attackMode, float walkAnimSpeed, byte attackTimer) {
        this.bodyId = bodyId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rotX = rotX;
        this.rotY = rotY;
        this.attackMode = attackMode;
        this.walkAnimSpeed = walkAnimSpeed;
        this.attackTimer = attackTimer;
        byte[] key = LALNetwork.getServerSecret();
        ByteBuffer bb = ByteBuffer.allocate(16 + 8 + 8 + 8 + 4 + 4 + 1 + 4 + 1);
        bb.putLong(bodyId.getMostSignificantBits());
        bb.putLong(bodyId.getLeastSignificantBits());
        bb.putDouble(x);
        bb.putDouble(y);
        bb.putDouble(z);
        bb.putFloat(rotX);
        bb.putFloat(rotY);
        bb.put(attackMode);
        bb.putFloat(walkAnimSpeed);
        bb.put(attackTimer);
        this.hmac = LALNetwork.computeHmac(key, bb.array());
    }

    private LALEntitySyncPacket(UUID bodyId, double x, double y, double z, float rotX, float rotY, byte attackMode, float walkAnimSpeed, byte attackTimer, byte[] hmac) {
        this.bodyId = bodyId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rotX = rotX;
        this.rotY = rotY;
        this.attackMode = attackMode;
        this.walkAnimSpeed = walkAnimSpeed;
        this.attackTimer = attackTimer;
        this.hmac = hmac;
    }

    @Override
    public byte getPacketType() {
        return LALNetwork.PKT_ENTITY_SYNC;
    }

    @Override
    public void encodeTo(FriendlyByteBuf buf) {
        buf.writeUUID(bodyId);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(rotX);
        buf.writeFloat(rotY);
        buf.writeByte(attackMode);
        buf.writeFloat(walkAnimSpeed);
        buf.writeByte(attackTimer);
        buf.writeByteArray(hmac);
    }

    public static LALEntitySyncPacket decode(FriendlyByteBuf buf) {
        UUID bodyId = buf.readUUID();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float rotX = buf.readFloat();
        float rotY = buf.readFloat();
        byte attackMode = buf.readByte();
        float walkAnimSpeed = buf.readFloat();
        byte attackTimer = buf.readByte();
        byte[] hmac = buf.readByteArray(256);
        return new LALEntitySyncPacket(bodyId, x, y, z, rotX, rotY, attackMode, walkAnimSpeed, attackTimer, hmac);
    }

    public static void decodeAndHandle(FriendlyByteBuf buf) {
        LALEntitySyncPacket msg = decode(buf);
        byte[] clientKey = LALNetwork.getClientSecret();
        if (clientKey != null && msg.hmac != null && msg.hmac.length > 0) {
            ByteBuffer bb = ByteBuffer.allocate(16 + 8 + 8 + 8 + 4 + 4 + 1 + 4 + 1);
            bb.putLong(msg.bodyId.getMostSignificantBits());
            bb.putLong(msg.bodyId.getLeastSignificantBits());
            bb.putDouble(msg.x);
            bb.putDouble(msg.y);
            bb.putDouble(msg.z);
            bb.putFloat(msg.rotX);
            bb.putFloat(msg.rotY);
            bb.put(msg.attackMode);
            bb.putFloat(msg.walkAnimSpeed);
            bb.put(msg.attackTimer);
            if (!LALNetwork.verifyHmac(clientKey, bb.array(), msg.hmac)) {
                return;
            }
        }
        handleClient(msg.bodyId, msg.x, msg.y, msg.z, msg.rotX, msg.rotY, msg.attackMode, msg.walkAnimSpeed, msg.attackTimer);
    }

    private static void handleClient(UUID bodyId, double x, double y, double z, float rotX, float rotY, byte attackMode, float walkAnimSpeed, byte attackTimer) {
        jp.mikumiku.lal.entity.LALEntityClientStateStore.update(bodyId, x, y, z, rotX, rotY, attackMode & 0xFF, walkAnimSpeed, attackTimer & 0xFF);
    }
}
