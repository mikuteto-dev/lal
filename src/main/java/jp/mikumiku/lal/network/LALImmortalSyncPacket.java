package jp.mikumiku.lal.network;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

public class LALImmortalSyncPacket implements LALPacket {
    private final UUID targetUuid;
    private final boolean isImmortal;
    private final byte[] hmac;

    public LALImmortalSyncPacket(UUID targetUuid, boolean isImmortal) {
        this.targetUuid = targetUuid;
        this.isImmortal = isImmortal;
        byte[] key = LALNetwork.getServerSecret();
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(17);
        bb.putLong(targetUuid.getMostSignificantBits());
        bb.putLong(targetUuid.getLeastSignificantBits());
        bb.put((byte) (isImmortal ? 1 : 0));
        this.hmac = LALNetwork.computeHmac(key, bb.array());
    }

    private LALImmortalSyncPacket(UUID targetUuid, boolean isImmortal, byte[] hmac) {
        this.targetUuid = targetUuid;
        this.isImmortal = isImmortal;
        this.hmac = hmac;
    }

    @Override
    public byte getPacketType() {
        return LALNetwork.PKT_IMMORTAL_SYNC;
    }

    @Override
    public void encodeTo(FriendlyByteBuf buf) {
        buf.writeUUID(targetUuid);
        buf.writeBoolean(isImmortal);
        buf.writeByteArray(hmac);
    }

    public static LALImmortalSyncPacket decode(FriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        boolean immortal = buf.readBoolean();
        byte[] hmac = buf.readByteArray(256);
        return new LALImmortalSyncPacket(uuid, immortal, hmac);
    }

    public static void decodeAndHandle(FriendlyByteBuf buf) {
        LALImmortalSyncPacket msg = decode(buf);
        byte[] clientKey = LALNetwork.getClientSecret();
        if (clientKey != null && msg.hmac != null && msg.hmac.length > 0) {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(17);
            bb.putLong(msg.targetUuid.getMostSignificantBits());
            bb.putLong(msg.targetUuid.getLeastSignificantBits());
            bb.put((byte) (msg.isImmortal ? 1 : 0));
            if (!LALNetwork.verifyHmac(clientKey, bb.array(), msg.hmac)) {
                return;
            }
        }
        try {
            Class<?> clientHandler = Class.forName("jp.mikumiku.lal.client.LALClientHandler");
            java.lang.reflect.Method method = clientHandler.getMethod("handleImmortalSync", UUID.class, boolean.class);
            method.invoke(null, msg.targetUuid, msg.isImmortal);
        } catch (Throwable ignored) {}
    }
}
