package jp.mikumiku.lal.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public class RemoveEntityPacket implements LALPacket {
    private final int entityId;
    private final byte[] hmac;

    public RemoveEntityPacket(int entityId) {
        this.entityId = entityId;
        byte[] key = LALNetwork.getServerSecret();
        byte[] data = java.nio.ByteBuffer.allocate(4).putInt(entityId).array();
        this.hmac = LALNetwork.computeHmac(key, data);
    }

    private RemoveEntityPacket(int entityId, byte[] hmac) {
        this.entityId = entityId;
        this.hmac = hmac;
    }

    @Override
    public byte getPacketType() {
        return LALNetwork.PKT_REMOVE_ENTITY;
    }

    @Override
    public void encodeTo(FriendlyByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeByteArray(hmac);
    }

    public static RemoveEntityPacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        byte[] hmac = buf.readByteArray(256);
        return new RemoveEntityPacket(entityId, hmac);
    }

    public static void decodeAndHandle(FriendlyByteBuf buf) {
        RemoveEntityPacket msg = decode(buf);
        if (msg.entityId <= 0) return;
        byte[] clientKey = LALNetwork.getClientSecret();
        if (clientKey != null && msg.hmac != null && msg.hmac.length > 0) {
            byte[] data = java.nio.ByteBuffer.allocate(4).putInt(msg.entityId).array();
            if (!LALNetwork.verifyHmac(clientKey, data, msg.hmac)) {
                return;
            }
        }
        handleClient(msg.entityId);
    }

    private static void handleClient(int entityId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level != null) {
                Entity entity = level.getEntity(entityId);
                if (entity != null) {
                    if (entity == mc.player || entity instanceof Player) {
                        return;
                    }
                    try {
                        entity.setRemoved(Entity.RemovalReason.KILLED);
                    } catch (Throwable ignored) {}
                    if (!entity.isRemoved()) {
                        try {
                            java.lang.reflect.Field f = null;
                            for (String name : new String[]{"removalReason", "f_146795_"}) {
                                try {
                                    f = Entity.class.getDeclaredField(name);
                                    f.setAccessible(true);
                                    break;
                                } catch (NoSuchFieldException ignored2) {}
                            }
                            if (f != null) {
                                f.set(entity, Entity.RemovalReason.KILLED);
                            }
                        } catch (Throwable ignored) {}
                    }
                    try {
                        entity.kill();
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }
}
