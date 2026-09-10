package jp.mikumiku.lal.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java base and java.instrument only. The agent class named in the jar is loaded by the system
 * class loader, which cannot see this mod or its dependencies, so naming LALAgent there can
 * only produce ClassNotFoundException; this captures the handle and publishes it instead.
 */
public final class LALBootstrap {

            /** The key LALAgent and LALAgentBridge already read. */
    public static final String INSTRUMENTATION_KEY = "\0lal\0i";

    private LALBootstrap() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        capture(instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        capture(instrumentation);
    }

    private static void capture(Instrumentation instrumentation) {
        if (instrumentation == null) return;
        try {
            System.getProperties().put(INSTRUMENTATION_KEY, instrumentation);
        } catch (Throwable ignored) {
        }
    }
}
