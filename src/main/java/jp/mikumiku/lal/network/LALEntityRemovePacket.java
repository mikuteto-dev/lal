package jp.mikumiku.lal.network;

import net.minecraft.network.FriendlyByteBuf;

import java.nio.ByteBuffer;
import java.util.UUID;

public class LALEntityRemovePacket implements LALPacket {
    private final UUID bodyId;
    private final byte[] hmac;

    public LALEntityRemovePacket(UUID bodyId) {
        this.bodyId = bodyId;
        byte[] key = LALNetwork.getServerSecret();
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(bodyId.getMostSignificantBits());
        bb.putLong(bodyId.getLeastSignificantBits());
        this.hmac = LALNetwork.computeHmac(key, bb.array());
    }

    private LALEntityRemovePacket(UUID bodyId, byte[] hmac) {
        this.bodyId = bodyId;
        this.hmac = hmac;
    }

    @Override
    public byte getPacketType() {
        return LALNetwork.PKT_ENTITY_REMOVE;
    }

    @Override
    public void encodeTo(FriendlyByteBuf buf) {
        buf.writeUUID(bodyId);
        buf.writeByteArray(hmac);
    }

    public static LALEntityRemovePacket decode(FriendlyByteBuf buf) {
        UUID bodyId = buf.readUUID();
        byte[] hmac = buf.readByteArray(256);
        return new LALEntityRemovePacket(bodyId, hmac);
    }

    public static void decodeAndHandle(FriendlyByteBuf buf) {
        LALEntityRemovePacket msg = decode(buf);
        byte[] clientKey = LALNetwork.getClientSecret();
        if (clientKey != null && msg.hmac != null && msg.hmac.length > 0) {
            ByteBuffer bb = ByteBuffer.allocate(16);
            bb.putLong(msg.bodyId.getMostSignificantBits());
            bb.putLong(msg.bodyId.getLeastSignificantBits());
            if (!LALNetwork.verifyHmac(clientKey, bb.array(), msg.hmac)) {
                return;
            }
        }
        handleClient(msg.bodyId);
    }

    private static void handleClient(UUID bodyId) {
        jp.mikumiku.lal.core.LALAccessChecker.performPrivilegedAction(() -> {
            jp.mikumiku.lal.entity.LALEntityClientStateStore.remove(bodyId);
        });
    }
}
