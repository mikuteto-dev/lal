package jp.mikumiku.lal.agent;

import java.lang.instrument.Instrumentation;

public class LALAgentBridge {
    private static volatile Instrumentation instrumentation;
    private static volatile boolean agentReady;

    public LALAgentBridge() {
        super();
    }

    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
        agentReady = true;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static boolean isAgentReady() {
        return agentReady;
    }
}

