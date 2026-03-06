package jp.mikumiku.lal.util;

import java.lang.reflect.Field;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

public class MixinUtil {

    private static volatile Field cancelledField;
    private static volatile boolean cancelledFieldResolved = false;

    public static void forceUncancel(CallbackInfo ci) {
        try {
            Field f = cancelledField;
            if (f == null && !cancelledFieldResolved) {
                f = resolveCancelledField(ci);
            }
            if (f != null) {
                f.setBoolean(ci, false);
            }
        } catch (Exception ignored) {}
    }

    private static Field resolveCancelledField(CallbackInfo ci) {
        try {
            Field f = CallbackInfo.class.getDeclaredField("cancelled");
            f.setAccessible(true);
            cancelledField = f;
            cancelledFieldResolved = true;
            return f;
        } catch (NoSuchFieldException ignored) {}
        try {
            for (Field candidate : CallbackInfo.class.getDeclaredFields()) {
                if (candidate.getType() == boolean.class) {
                    candidate.setAccessible(true);
                    if (ci.isCancelled()) {
                        boolean val = candidate.getBoolean(ci);
                        if (val) {
                            cancelledField = candidate;
                            cancelledFieldResolved = true;
                            return candidate;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        cancelledFieldResolved = true;
        return null;
    }

    public static Field getCancelledField(CallbackInfo ci) {
        Field f = cancelledField;
        if (f != null) return f;
        if (cancelledFieldResolved) return null;
        return resolveCancelledField(ci);
    }

    public static float safeMaxHealth(LivingEntity entity) {
        float max = entity.getMaxHealth();
        if (max <= 0.0f) max = 20.0f;
        return max;
    }
}
