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
        // Off by default. Participating in ModLauncher's own class pipeline - TransformerClassWriter
        // rebuilds a class with the computing_frames reason to resolve a supertype - makes
        // ModuleClassLoader fail to resolve vanilla packages for real class loads, which surfaces as
        // ClassNotFoundException: Entity / Player from ServerLevel.tick. Mixin already injects the
        // same hooks into the same classes, so the enforcement does not depend on this. Set
        // -Dlal.asm=true to enable the table.
        boolean enabled = Boolean.getBoolean("lal.asm");
        try {
            org.apache.logging.log4j.LogManager.getLogger("lal")
                    .info("[LAL] transformer service ACTIVE, ASM hook table {}",
                            enabled ? "ENABLED" : "disabled (Mixin carries the hooks)");
        } catch (Throwable ignored) {
        }
        return enabled ? List.of(new LALClassTransformer()) : List.of();
    }

}
