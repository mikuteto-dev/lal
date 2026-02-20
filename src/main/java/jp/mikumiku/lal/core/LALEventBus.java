package jp.mikumiku.lal.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraftforge.common.MinecraftForge;

public class LALEventBus {
    private static final Set<Object> PROTECTED_LISTENERS = ConcurrentHashMap.newKeySet();

    public static void protectAndRegister(Object listener) {
        PROTECTED_LISTENERS.add(listener);
        try {
            MinecraftForge.EVENT_BUS.register(listener);
        } catch (Throwable ignored) {}
    }

    public static void reRegisterAll() {
        for (Object listener : PROTECTED_LISTENERS) {
            try {
                MinecraftForge.EVENT_BUS.register(listener);
            } catch (Throwable ignored) {}
        }
    }

    public static Set<Object> getProtectedListeners() {
        return PROTECTED_LISTENERS;
    }
}
