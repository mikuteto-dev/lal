package jp.mikumiku.lal.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class LALNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("lal", "main"),
            () -> PROTOCOL_VERSION,
            s -> true,
            s -> true
    );

    private static int packetId = 0;

    public static void register() {
        CHANNEL.registerMessage(packetId++, RemoveEntityPacket.class,
                RemoveEntityPacket::encode,
                RemoveEntityPacket::decode,
                RemoveEntityPacket::handle);
    }

    public static void broadcastRemoveEntity(ServerLevel level, int entityId) {
        RemoveEntityPacket packet = new RemoveEntityPacket(entityId);
        for (ServerPlayer player : level.players()) {
            try {
                CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            } catch (Exception ignored) {}
        }
    }
}
