package jp.mikumiku.lal.core;

import jp.mikumiku.lal.agent.LALAgentBridge;
import jp.mikumiku.lal.transformer.LALTransformationService;
import jp.mikumiku.lal.transformer.LALTransformer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * A dead wiring used to be indistinguishable from a working one, because every failure in it was
 * swallowed. Dev runs report the transformer as inactive by design: Forge's dev discovery skips
 * directory classpath entries, so a transformation service is found only from a jar in mods/.
 */
public final class LALStartupReport {

    private static volatile boolean reported = false;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (reported) return;
        reported = true;
        try {
            org.apache.logging.log4j.LogManager.getLogger("lal").info(
                    "[LAL] transformer service {} | transformer classes={} methods={} skipped={} | java agent {}",
                    LALTransformationService.isServiceActive() ? "ACTIVE" : "INACTIVE",
                    LALTransformer.getTransformedClassCount(),
                    LALTransformer.getTransformedMethodCount(),
                    LALTransformer.getSkippedClassCount(),
                    LALAgentBridge.isAgentReady() ? "attached" : "absent");
        } catch (Throwable ignored) {
        }
    }
}
