package jp.mikumiku.lal.network;

import net.minecraft.network.FriendlyByteBuf;

public interface LALPacket {
    byte getPacketType();
    void encodeTo(FriendlyByteBuf buf);
}
