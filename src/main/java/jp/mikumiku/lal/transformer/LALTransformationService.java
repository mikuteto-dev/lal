package jp.mikumiku.lal.transformer;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.util.List;
import java.util.Set;

public class LALTransformationService implements ITransformationService {

    private static volatile boolean serviceActive = false;

    /**
     * True only for a jar in mods/: Forge's dev discovery skips directory classpath entries, so a
     * dev run reports false while the mod is running.
     */
    public static boolean isServiceActive() {
        return serviceActive;
    }

    @Override
    public String name() {
        return "lal_service";
    }

    @Override
    public void initialize(IEnvironment environment) {
        serviceActive = true;
        try {
            jp.mikumiku.lal.enforcement.PluginDefender.initialize();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<ITransformer> transformers() {
        try {
            org.apache.logging.log4j.LogManager.getLogger("lal")
                    .info("[LAL] transformer service ACTIVE");
        } catch (Throwable ignored) {
        }
        return List.of(new LALClassTransformer());
    }

}
