package jp.mikumiku.lal.agent;

import java.lang.instrument.Instrumentation;

public class LALAgentBridge {
    private static volatile Instrumentation instrumentation;
    private static volatile Instrumentation backupInstrumentation;
    private static volatile boolean agentReady;
    private static volatile boolean nativeAvailable;

    static {
        try {
            nativeAvailable = jp.mikumiku.lal.util.NativeLoader.ensureLoaded();
        } catch (Throwable t) {
            nativeAvailable = false;
        }
    }

    private static native Object nativeGetInstrumentation();

    public LALAgentBridge() {
        super();
    }

    public static void setInstrumentation(Instrumentation inst) {
        if (isValidInstrumentation(inst)) {
            instrumentation = inst;
            backupInstrumentation = inst;
            agentReady = true;
            try { System.getProperties().put("\0lal\0i", inst); } catch (Throwable ignored) {}
            try { LALAgent.storeInstrumentationInHidden(inst); } catch (Throwable ignored) {}
        }
    }

    public static Instrumentation getInstrumentation() {
        Instrumentation inst = instrumentation;
        if (inst != null && !isValidInstrumentation(inst)) {
            if (backupInstrumentation != null && isValidInstrumentation(backupInstrumentation)) {
                instrumentation = backupInstrumentation;
                return backupInstrumentation;
            }
            try {
                Instrumentation recovered = recoverInstrumentation();
                if (recovered != null) {
                    instrumentation = recovered;
                    backupInstrumentation = recovered;
                    return recovered;
                }
            } catch (Throwable ignored) {}
        }
        return inst;
    }

    public static boolean isAgentReady() {
        return agentReady;
    }

    public static boolean isValidInstrumentation(Instrumentation inst) {
        if (inst == null) return false;
        try {
            String className = inst.getClass().getName();
            if (className.equals("sun.instrument.InstrumentationImpl")) return true;
            Class<?>[] loaded = inst.getAllLoadedClasses();
            return loaded != null && loaded.length > 10;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void verifyAndRestore() {
        try {
            Instrumentation inst = instrumentation;
            if (inst == null) return;
            if (!isValidInstrumentation(inst)) {
                if (backupInstrumentation != null && isValidInstrumentation(backupInstrumentation)) {
                    instrumentation = backupInstrumentation;
                } else {
                    Instrumentation recovered = recoverInstrumentation();
                    if (recovered != null) {
                        instrumentation = recovered;
                        backupInstrumentation = recovered;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Instrumentation recoverInstrumentation() {
        try {
            Instrumentation hidden = LALAgent.recoverInstrumentationFromHidden();
            if (hidden != null) return hidden;
        } catch (Throwable ignored) {}
        try {
            Instrumentation bs = LALAgent.recoverFromBootstrap();
            if (bs != null) return bs;
        } catch (Throwable ignored) {}
        try {
            Object bootstrap = System.getProperties().get("\0lal\0i");
            if (bootstrap instanceof Instrumentation bi && isValidInstrumentation(bi)) return bi;
        } catch (Throwable ignored) {}
        try {
            Class<?> sunInstImpl = Class.forName("sun.instrument.InstrumentationImpl");
            for (java.lang.reflect.Field f : sunInstImpl.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) &&
                        Instrumentation.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val instanceof Instrumentation candidate && isValidInstrumentation(candidate)) {
                        return candidate;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (nativeAvailable) {
            try {
                Object nativeResult = nativeGetInstrumentation();
                if (nativeResult instanceof Instrumentation nativeInst && isValidInstrumentation(nativeInst)) {
                    return nativeInst;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}

