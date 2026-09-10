import java.nio.file.*;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import cpw.mods.modlauncher.api.NamedPath;

/**
 * Runs Forge's real discovery implementation (not a reimplementation of its filter) against a
 * game directory containing the built jar in mods/, which is exactly what a normal install is.
 */
public class DiscoveryCheck2 {
    public static void main(String[] args) throws Exception {
        Path src = Path.of(args[0]);
        Path game = Files.createTempDirectory("lalfakegame");
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        Path installed = mods.resolve(src.getFileName().toString());
        Files.copy(src, installed, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("simulated install: " + installed);

        // Real class, real filter.
        var discoverer = new ModDirTransformerDiscoverer();
        var found = discoverer.candidates(game, "forgeserver");

        System.out.println("discovered " + found.size() + " entry/entries:");
        boolean sawTransformService = false;
        for (NamedPath np : found) {
            System.out.println("  " + np.name() + " -> " + java.util.Arrays.toString(np.paths()));
            if ("cpw.mods.modlauncher.api.ITransformationService".equals(np.name())) sawTransformService = true;
        }
        // The real requirement is that the jar loads as a MOD. A jar that supplies a discovered
        // transformation service is filtered out of the mods scan (ModsFolderLocator excludes
        // ModDirTransformerDiscoverer.allExcluded()), so being discovered as a service is a
        // failure, not a success - that is what silently removed the items and the creative tab.
        boolean stillAMod = !ModDirTransformerDiscoverer.allExcluded().contains(installed);
        System.out.println("excluded from the mods scan (would remove items): " + !stillAMod);
        if (sawTransformService) {
            System.out.println("FAIL: the jar supplies a transformation service, so Forge will not load it as a mod");
            System.exit(1);
        }
        if (!stillAMod) {
            System.out.println("FAIL: the jar is excluded from the mods scan");
            System.exit(1);
        }
        System.out.println("RESULT: PASS (loaded as a mod, no transformation service claimed)");
    }
}
