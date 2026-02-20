package jp.mikumiku.lal.enforcement;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import jp.mikumiku.lal.agent.LALAgent;
import jp.mikumiku.lal.agent.LALAgentBridge;

public class DaemonWatchdog {

    private static volatile Thread watchdogThread = null;
    private static volatile boolean running = false;

    public static void start() {
        if (running && watchdogThread != null && watchdogThread.isAlive()) {
            return;
        }
        running = true;
        watchdogThread = new Thread(DaemonWatchdog::run, "LAL-Watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.setPriority(Thread.MAX_PRIORITY - 1);
        watchdogThread.setUncaughtExceptionHandler((t, e) -> {
            running = false;
            try { start(); } catch (Throwable ignored) {}
        });
        watchdogThread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> running = false));
    }

    private static void run() {
        while (running) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
            try {
                EnforcementDaemon.ensureRunning();
            } catch (Throwable ignored) {}
            try {
                restoreTransformerIfNeeded();
            } catch (Throwable ignored) {}
        }
    }

    private static void restoreTransformerIfNeeded() {
        try {
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
            for (Object info : transformerList) {
                if (info == null) continue;
                try {
                    Object transformer = mTransformerField.get(info);
                    if (transformer == null) continue;
                    String className = transformer.getClass().getName();
                    if (className.contains("Empty") || className.contains("Null")) {
                        LALAgent.retransformTargetClasses();
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}
