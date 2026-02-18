package jp.mikumiku.lal.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class RemoveEntityPacket {
    private final int entityId;

    public RemoveEntityPacket(int entityId) {
        this.entityId = entityId;
    }

    public static void encode(RemoveEntityPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
    }

    public static RemoveEntityPacket decode(FriendlyByteBuf buf) {
        return new RemoveEntityPacket(buf.readInt());
    }

    public static void handle(RemoveEntityPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg.entityId));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleClient(int entityId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level != null) {
                Entity entity = level.getEntity(entityId);
                if (entity != null) {
                    entity.setRemoved(Entity.RemovalReason.KILLED);
                }
            }
        } catch (Exception ignored) {}
    }
}
