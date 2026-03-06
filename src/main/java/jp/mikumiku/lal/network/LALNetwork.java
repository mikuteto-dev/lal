package jp.mikumiku.lal.network;

import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class LALNetwork {
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation("lal", "main");
    public static final ResourceLocation CHANNEL_BACKUP_ID = new ResourceLocation("lal", "sync");
    public static final ResourceLocation CHANNEL_TERTIARY_ID = new ResourceLocation("lal", "data");
    private static final ResourceLocation[] ALL_CHANNELS = { CHANNEL_ID, CHANNEL_BACKUP_ID, CHANNEL_TERTIARY_ID };

    public static final byte PKT_REMOVE_ENTITY = 0;
    public static final byte PKT_ENTITY_SYNC = 1;
    public static final byte PKT_ENTITY_SPAWN = 2;
    public static final byte PKT_ENTITY_REMOVE = 3;
    public static final byte PKT_ENTITY_EFFECT = 4;
    public static final byte PKT_IMMORTAL_SYNC = 5;
    public static final byte PKT_KEY_EXCHANGE = 6;

    private static volatile byte[] serverSecret;
    private static volatile byte[] clientSecret;

    public static void initServerSecret() {
        if (serverSecret == null) {
            serverSecret = new byte[32];
            new SecureRandom().nextBytes(serverSecret);
        }
    }

    public static byte[] getServerSecret() {
        if (serverSecret == null) initServerSecret();
        return serverSecret;
    }

    public static void setClientSecret(byte[] secret) {
        clientSecret = secret;
    }

    public static byte[] getClientSecret() {
        return clientSecret;
    }

    public static byte[] computeHmac(byte[] key, byte[] data) {
        if (key == null || key.length == 0 || data == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean verifyHmac(byte[] key, byte[] data, byte[] signature) {
        if (key == null || key.length == 0 || data == null || signature == null || signature.length == 0) return false;
        try {
            byte[] expected = computeHmac(key, data);
            if (expected == null || expected.length != signature.length) return false;
            int result = 0;
            for (int i = 0; i < expected.length; i++) {
                result |= expected[i] ^ signature[i];
            }
            return result == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void register() {
    }

    private static void sendSingle(LALPacket packet, ServerPlayer player, ResourceLocation channel) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        boolean sent = false;
        try {
            buf.writeByte(packet.getPacketType());
            packet.encodeTo(buf);
            player.connection.send(new ClientboundCustomPayloadPacket(channel, buf));
            sent = true;
        } catch (Throwable ignored) {
        } finally {
            if (!sent && buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    public static void sendToPlayer(LALPacket packet, ServerPlayer player) {
        try {
            sendSingle(packet, player, CHANNEL_ID);
        } catch (Throwable ignored) {}
    }

    public static void sendRedundant(LALPacket packet, ServerPlayer player) {
        for (ResourceLocation ch : ALL_CHANNELS) {
            try {
                sendSingle(packet, player, ch);
            } catch (Throwable ignored) {}
        }
    }

    public static void broadcastImmortalSync(ServerLevel level, UUID targetUuid, boolean isImmortal) {
        LALImmortalSyncPacket packet = new LALImmortalSyncPacket(targetUuid, isImmortal);
        java.util.List<ServerPlayer> players;
        try { players = new java.util.ArrayList<>(level.players()); } catch (Throwable t) { return; }
        for (ServerPlayer player : players) {
            for (ResourceLocation ch : ALL_CHANNELS) {
                try {
                    sendSingle(packet, player, ch);
                } catch (Throwable ignored) {}
            }
        }
    }

    public static void sendKeyToPlayer(ServerPlayer player) {
        try {
            initServerSecret();
            LALKeyExchangePacket packet = new LALKeyExchangePacket(serverSecret);
            for (ResourceLocation ch : ALL_CHANNELS) {
                try {
                    sendSingle(packet, player, ch);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void broadcastRemoveEntity(ServerLevel level, int entityId) {
        RemoveEntityPacket packet = new RemoveEntityPacket(entityId);
        java.util.List<ServerPlayer> players;
        try { players = new java.util.ArrayList<>(level.players()); } catch (Throwable t) { return; }
        for (ServerPlayer player : players) {
            for (ResourceLocation ch : ALL_CHANNELS) {
                try {
                    sendSingle(packet, player, ch);
                } catch (Throwable ignored) {}
            }
        }
    }

    public static ResourceLocation[] getAllChannels() {
        return ALL_CHANNELS;
    }
}
