package jp.mikumiku.lal.network;

import net.minecraft.network.FriendlyByteBuf;

public class LALPacketHandler {
    private static final long[] RECENT_HASHES = new long[32];
    private static int hashIndex = 0;

    private static long computePayloadHash(FriendlyByteBuf buf) {
        int readerIdx = buf.readerIndex();
        long h = 0xcbf29ce484222325L;
        while (buf.isReadable()) {
            h ^= buf.readByte();
            h *= 0x100000001b3L;
        }
        buf.readerIndex(readerIdx);
        return h;
    }

    private static boolean isDuplicate(long hash) {
        for (int i = 0; i < RECENT_HASHES.length; i++) {
            if (RECENT_HASHES[i] == hash) return true;
        }
        return false;
    }

    private static void recordHash(long hash) {
        RECENT_HASHES[hashIndex % RECENT_HASHES.length] = hash;
        hashIndex++;
    }

    public static void handleClientPayload(FriendlyByteBuf buf) {
        try {
            long hash = computePayloadHash(buf);
            if (isDuplicate(hash)) return;
            recordHash(hash);
            byte type = buf.readByte();
            switch (type) {
                case LALNetwork.PKT_REMOVE_ENTITY:
                    RemoveEntityPacket.decodeAndHandle(buf);
                    break;
                case LALNetwork.PKT_ENTITY_SYNC:
                    LALEntitySyncPacket.decodeAndHandle(buf);
                    break;
                case LALNetwork.PKT_ENTITY_SPAWN:
                    LALEntitySpawnPacket.decodeAndHandle(buf);
                    break;
                case LALNetwork.PKT_ENTITY_REMOVE:
                    LALEntityRemovePacket.decodeAndHandle(buf);
                    break;
                case LALNetwork.PKT_ENTITY_EFFECT:
                    LALEntityEffectPacket.decodeAndHandle(buf);
                    break;
                case LALNetwork.PKT_IMMORTAL_SYNC:
                    LALImmortalSyncPacket.decodeAndHandle(buf);
                    break;
                case LALNetwork.PKT_KEY_EXCHANGE:
                    LALKeyExchangePacket.decodeAndHandle(buf);
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {}
    }
}
