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
        System.out.println(sawTransformService ? "RESULT: PASS" : "RESULT: FAIL (mod jar not discovered)");
        if (!sawTransformService) System.exit(1);
    }
}
