package jp.mikumiku.lal.network;

import net.minecraft.network.FriendlyByteBuf;

public class LALKeyExchangePacket implements LALPacket {
    private static final byte[] SCRAMBLE_KEY = {
        (byte)0xA3, (byte)0x7F, (byte)0x1B, (byte)0xD4, (byte)0x92, (byte)0x56, (byte)0xE8, (byte)0x0C,
        (byte)0x4F, (byte)0xB1, (byte)0x63, (byte)0x9A, (byte)0x27, (byte)0xDE, (byte)0x85, (byte)0x70,
        (byte)0xC6, (byte)0x3D, (byte)0xF9, (byte)0x14, (byte)0xAB, (byte)0x58, (byte)0x02, (byte)0xEF,
        (byte)0x6D, (byte)0x81, (byte)0xCA, (byte)0x39, (byte)0xF5, (byte)0x47, (byte)0xB8, (byte)0x2E
    };
    private final byte[] key;

    public LALKeyExchangePacket(byte[] key) {
        this.key = key;
    }

    private static byte[] scramble(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte)(data[i] ^ SCRAMBLE_KEY[i % SCRAMBLE_KEY.length] ^ (byte)(i * 0x9E + 0x3B));
        }
        return result;
    }

    @Override
    public byte getPacketType() {
        return LALNetwork.PKT_KEY_EXCHANGE;
    }

    @Override
    public void encodeTo(FriendlyByteBuf buf) {
        buf.writeByteArray(scramble(key));
    }

    public static LALKeyExchangePacket decode(FriendlyByteBuf buf) {
        byte[] scrambled = buf.readByteArray(256);
        return new LALKeyExchangePacket(scramble(scrambled));
    }

    public static void decodeAndHandle(FriendlyByteBuf buf) {
        LALKeyExchangePacket msg = decode(buf);
        LALNetwork.setClientSecret(msg.key);
    }
}
