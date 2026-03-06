package jp.mikumiku.lal.network;

import net.minecraft.network.FriendlyByteBuf;

import java.nio.ByteBuffer;
import java.util.UUID;

public class LALEntitySpawnPacket implements LALPacket {
    private final UUID bodyId;
    private final double x;
    private final double y;
    private final double z;
    private final byte[] hmac;

    public LALEntitySpawnPacket(UUID bodyId, double x, double y, double z) {
        this.bodyId = bodyId;
        this.x = x;
        this.y = y;
        this.z = z;
        byte[] key = LALNetwork.getServerSecret();
        ByteBuffer bb = ByteBuffer.allocate(16 + 8 + 8 + 8);
        bb.putLong(bodyId.getMostSignificantBits());
        bb.putLong(bodyId.getLeastSignificantBits());
        bb.putDouble(x);
        bb.putDouble(y);
        bb.putDouble(z);
        this.hmac = LALNetwork.computeHmac(key, bb.array());
    }

    private LALEntitySpawnPacket(UUID bodyId, double x, double y, double z, byte[] hmac) {
        this.bodyId = bodyId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.hmac = hmac;
    }

    @Override
    public byte getPacketType() {
        return LALNetwork.PKT_ENTITY_SPAWN;
    }

    @Override
    public void encodeTo(FriendlyByteBuf buf) {
        buf.writeUUID(bodyId);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeByteArray(hmac);
    }

    public static LALEntitySpawnPacket decode(FriendlyByteBuf buf) {
        UUID bodyId = buf.readUUID();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        byte[] hmac = buf.readByteArray(256);
        return new LALEntitySpawnPacket(bodyId, x, y, z, hmac);
    }

    public static void decodeAndHandle(FriendlyByteBuf buf) {
        LALEntitySpawnPacket msg = decode(buf);
        if (msg.bodyId == null) return;
        if (!Double.isFinite(msg.x) || !Double.isFinite(msg.y) || !Double.isFinite(msg.z)) return;
        byte[] clientKey = LALNetwork.getClientSecret();
        if (clientKey != null && msg.hmac != null && msg.hmac.length > 0) {
            ByteBuffer bb = ByteBuffer.allocate(16 + 8 + 8 + 8);
            bb.putLong(msg.bodyId.getMostSignificantBits());
            bb.putLong(msg.bodyId.getLeastSignificantBits());
            bb.putDouble(msg.x);
            bb.putDouble(msg.y);
            bb.putDouble(msg.z);
            if (!LALNetwork.verifyHmac(clientKey, bb.array(), msg.hmac)) {
                return;
            }
        }
        handleClient(msg.bodyId, msg.x, msg.y, msg.z);
    }

    private static void handleClient(UUID bodyId, double x, double y, double z) {
        jp.mikumiku.lal.entity.LALEntityClientStateStore.add(bodyId, x, y, z);
    }
}
