package jp.mikumiku.lal.enforcement;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.UUID;
import jp.mikumiku.lal.agent.LALAgent;
import jp.mikumiku.lal.agent.LALAgentBridge;
import jp.mikumiku.lal.transformer.EntityMethodHooks;

public class DaemonWatchdog {

    private static volatile Thread watchdogThread = null;
    private static volatile boolean running = false;
    private static volatile boolean shutdownHookRegistered = false;
    private static volatile int verifyIndex = 0;
    private static final Class<?>[] TARGET_CLASSES = new Class<?>[5];
    private static final int[] EXPECTED_METHOD_COUNTS = new int[5];
    private static final Class<?>[] EXPECTED_SUPERS = new Class<?>[5];


    public static void start() {
        if (running && watchdogThread != null && watchdogThread.isAlive()) {
            return;
        }
        running = true;
        watchdogThread = new Thread(DaemonWatchdog::run, "Thread-" + UUID.randomUUID().toString().substring(0, 8)) {
            @Override
            public void interrupt() {
                if (!jp.mikumiku.lal.core.LALAccessChecker.isCallerFromLAL()) return;
                super.interrupt();
            }
            @Override
            public boolean isInterrupted() {
                if (!jp.mikumiku.lal.core.LALAccessChecker.isCallerFromLAL()) return false;
                return super.isInterrupted();
            }
            @Override
            public StackTraceElement[] getStackTrace() {
                if (!jp.mikumiku.lal.core.LALAccessChecker.isCallerFromLAL()) return new StackTraceElement[0];
                return super.getStackTrace();
            }
        };
        watchdogThread.setDaemon(true);
        watchdogThread.setPriority(Thread.MAX_PRIORITY - 1);
        watchdogThread.setUncaughtExceptionHandler((t, e) -> {
            running = false;
            try { start(); } catch (Throwable ignored) {}
        });
        watchdogThread.start();
        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> running = false));
        }
        saveClassBaselines();
    }

    private static void saveClassBaselines() {
        try {
            TARGET_CLASSES[0] = net.minecraft.world.entity.Entity.class;
            TARGET_CLASSES[1] = net.minecraft.world.entity.LivingEntity.class;
            TARGET_CLASSES[2] = net.minecraft.world.entity.player.Player.class;
            TARGET_CLASSES[3] = net.minecraft.server.level.ServerPlayer.class;
            TARGET_CLASSES[4] = net.minecraft.server.level.ServerLevel.class;
            for (int i = 0; i < TARGET_CLASSES.length; i++) {
                if (TARGET_CLASSES[i] != null) {
                    try {
                        EXPECTED_METHOD_COUNTS[i] = TARGET_CLASSES[i].getDeclaredMethods().length;
                        EXPECTED_SUPERS[i] = TARGET_CLASSES[i].getSuperclass();
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void run() {
        while (running) {
            try {
                try {
                    Thread.interrupted();
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.interrupted();
                    continue;
                }
                try {
                    EnforcementDaemon.ensureRunning();
                } catch (Throwable ignored) {}
                try {
                    EnforcementDaemon.ensurePoolDaemonRunning();
                } catch (Throwable ignored) {}
                try {
                    restoreTransformerIfNeeded();
                } catch (Throwable ignored) {}
                try {
                    verifyClassIntegrity();
                } catch (Throwable ignored) {}
                try {
                    rotatingBytecodeVerify();
                } catch (Throwable ignored) {}
            } catch (ThreadDeath td) {
                continue;
            }
        }
    }

    private static void verifyClassIntegrity() {
        for (int i = 0; i < TARGET_CLASSES.length; i++) {
            try {
                if (TARGET_CLASSES[i] == null) continue;
                Class<?> currentSuper = TARGET_CLASSES[i].getSuperclass();
                if (EXPECTED_SUPERS[i] != null && currentSuper != EXPECTED_SUPERS[i]) {
                    EnforcementDaemon.escalate();
                    LALAgent.retransformTargetClasses();
                    return;
                }
            } catch (Throwable ignored) {}
        }
        try {
            LALAgentBridge.verifyAndRestore();
        } catch (Throwable ignored) {}
    }

    /**
     * Only on evidence: a stall in the monotonic hook counter is the only observable symptom of
     * having been unhooked. Retransformation deoptimises the target's compiled methods.
     */
    private static final long ROTATING_VERIFY_INTERVAL_MS = 30_000L;
    private static volatile long lastRotatingVerifyMs = 0L;
    private static long lastHookCallsObserved = -1L;
    private static int stalledHookObservations = 0;

    private static void rotatingBytecodeVerify() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastRotatingVerifyMs < ROTATING_VERIFY_INTERVAL_MS) return;
            lastRotatingVerifyMs = now;

            long calls = EntityMethodHooks.getTotalHookCalls();
            if (calls != lastHookCallsObserved) {
                lastHookCallsObserved = calls;
                stalledHookObservations = 0;
                return;
            }
            stalledHookObservations++;
            // An idle server legitimately stops calling hooks, so require two quiet intervals.
            if (stalledHookObservations < 2) return;

            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null) return;
            int idx = verifyIndex % TARGET_CLASSES.length;
            verifyIndex++;
            Class<?> target = TARGET_CLASSES[idx];
            if (target == null) return;
            try { inst.retransformClasses(target); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /**
     * The transformer list only changes when something deliberately removes it, so this is not worth
     * a Class.forName walk 10 times a second.
     */
    private static final long TRANSFORMER_CHECK_INTERVAL_MS = 30_000L;
    private static volatile long lastTransformerCheckMs = 0L;

    private static void restoreTransformerIfNeeded() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastTransformerCheckMs < TRANSFORMER_CHECK_INTERVAL_MS) return;
            lastTransformerCheckMs = now;
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null) return;
            Class<?> transformerManagerClass = null;
            try {
                transformerManagerClass = Class.forName("sun.instrument.TransformerManager");
            } catch (ClassNotFoundException e) {
                return;
            }
            Class<?> transformerInfoClass = null;
            try {
                transformerInfoClass = Class.forName("sun.instrument.TransformerManager$TransformerInfo");
            } catch (ClassNotFoundException e) {
                return;
            }
            Field mTransformerField = null;
            try {
                mTransformerField = transformerInfoClass.getDeclaredField("mTransformer");
                mTransformerField.setAccessible(true);
            } catch (Throwable e) {
                return;
            }
            Field transformersField = null;
            for (String name : new String[]{"mTransformerList", "mRetransformableTransformerList"}) {
                try {
                    transformersField = transformerManagerClass.getDeclaredField(name);
                    transformersField.setAccessible(true);
                    break;
                } catch (Throwable ignored) {}
            }
            if (transformersField == null) return;
            Field managerField = null;
            for (String name : new String[]{"mRetransformableTransformManager", "mTransformManager"}) {
                try {
                    managerField = inst.getClass().getDeclaredField(name);
                    managerField.setAccessible(true);
                    break;
                } catch (Throwable ignored) {}
            }
            if (managerField == null) return;
            Object manager = managerField.get(inst);
            if (manager == null) return;
            Object list = transformersField.get(manager);
            if (!(list instanceof java.util.List)) return;
            java.util.List<?> transformerList = (java.util.List<?>) list;
            boolean needsRetransform = false;
            for (Object info : transformerList) {
                if (info == null) continue;
                try {
                    Object transformer = mTransformerField.get(info);
                    if (transformer == null) continue;
                    String className = transformer.getClass().getName();
                    if (className.contains("Empty") || className.contains("Null")) {
                        needsRetransform = true;
                        break;
                    }
                    // Any other agent's transformer is legitimate, not tampering. Treating it
                    // as tampering forced a retransformTargetClasses() at every check, on a client
                    // that merely had another mod's agent attached, and that redefinition ran
                    // concurrently with Bootstrap.
                } catch (Throwable ignored) {}
            }
            if (needsRetransform) {
                LALAgent.retransformTargetClasses();
            }
        } catch (Throwable ignored) {}
    }
}
