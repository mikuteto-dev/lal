package jp.mikumiku.lal.network;

import net.minecraft.network.FriendlyByteBuf;

import java.nio.ByteBuffer;

public class LALEntityEffectPacket implements LALPacket {
    private final byte effectType;
    private final double x;
    private final double y;
    private final double z;
    private final double tx;
    private final double ty;
    private final double tz;
    private final byte[] hmac;

    public LALEntityEffectPacket(byte effectType, double x, double y, double z, double tx, double ty, double tz) {
        this.effectType = effectType;
        this.x = x;
        this.y = y;
        this.z = z;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        byte[] key = LALNetwork.getServerSecret();
        ByteBuffer bb = ByteBuffer.allocate(1 + 8 + 8 + 8 + 8 + 8 + 8);
        bb.put(effectType);
        bb.putDouble(x);
        bb.putDouble(y);
        bb.putDouble(z);
        bb.putDouble(tx);
        bb.putDouble(ty);
        bb.putDouble(tz);
        this.hmac = LALNetwork.computeHmac(key, bb.array());
    }

    private LALEntityEffectPacket(byte effectType, double x, double y, double z, double tx, double ty, double tz, byte[] hmac) {
        this.effectType = effectType;
        this.x = x;
        this.y = y;
        this.z = z;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.hmac = hmac;
    }

    @Override
    public byte getPacketType() {
        return LALNetwork.PKT_ENTITY_EFFECT;
    }

    @Override
    public void encodeTo(FriendlyByteBuf buf) {
        buf.writeByte(effectType);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeDouble(tx);
        buf.writeDouble(ty);
        buf.writeDouble(tz);
        buf.writeByteArray(hmac);
    }

    public static LALEntityEffectPacket decode(FriendlyByteBuf buf) {
        byte effectType = buf.readByte();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        double tx = buf.readDouble();
        double ty = buf.readDouble();
        double tz = buf.readDouble();
        byte[] hmac = buf.readByteArray(256);
        return new LALEntityEffectPacket(effectType, x, y, z, tx, ty, tz, hmac);
    }

    public static void decodeAndHandle(FriendlyByteBuf buf) {
        LALEntityEffectPacket msg = decode(buf);
        byte[] clientKey = LALNetwork.getClientSecret();
        if (clientKey != null && msg.hmac != null && msg.hmac.length > 0) {
            ByteBuffer bb = ByteBuffer.allocate(1 + 8 + 8 + 8 + 8 + 8 + 8);
            bb.put(msg.effectType);
            bb.putDouble(msg.x);
            bb.putDouble(msg.y);
            bb.putDouble(msg.z);
            bb.putDouble(msg.tx);
            bb.putDouble(msg.ty);
            bb.putDouble(msg.tz);
            if (!LALNetwork.verifyHmac(clientKey, bb.array(), msg.hmac)) {
                return;
            }
        }
        handleClient(msg.effectType, msg.x, msg.y, msg.z, msg.tx, msg.ty, msg.tz);
    }

    private static void handleClient(byte effectType, double x, double y, double z, double tx, double ty, double tz) {
        jp.mikumiku.lal.entity.LALEntityClientStateStore.addEffect(effectType & 0xFF, x, y, z, tx, ty, tz);
    }
}
