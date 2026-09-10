import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import cpw.mods.jarhandling.SecureJar;

/**
 * Reproduces Forge's mod-jar discovery (ModDirTransformerDiscoverer.visitFile) against the real
 * built artifact: it reads ModuleDescriptor.provides() and filters by the service names Forge
 * scans for. Nothing here is a LAL API - this is Forge's own jar handling.
 */
public class DiscoveryCheck {
    /** Copied from net.minecraftforge.fml.loading.ModDirTransformerDiscoverer.SERVICES. */
    static final Set<String> FORGE_SERVICES = Set.of(
            "cpw.mods.modlauncher.api.ITransformationService",
            "net.minecraftforge.forgespi.locating.IModLocator",
            "net.minecraftforge.forgespi.locating.IDependencyLocator",
            "net.minecraftforge.fml.loading.ImmediateWindowProvider");

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        System.out.println("jar = " + jar + " (" + Files.size(jar) + " bytes)");

        boolean ok = true;
        SecureJar secureJar = SecureJar.from(jar);
        var descriptor = secureJar.moduleDataProvider().descriptor();

        System.out.println("provides (as Forge sees them):");
        int hit = 0;
        for (var p : descriptor.provides()) {
            boolean scanned = FORGE_SERVICES.contains(p.service());
            System.out.println("  " + p.service() + " -> " + p.providers()
                    + (scanned ? "   [FORGE SCANS THIS]" : "   (not scanned by Forge)"));
            if (scanned) hit++;
        }
        if (hit == 0) {
            System.out.println("FAIL: Forge would discover nothing from this jar");
            ok = false;
        }

        // The provider class must exist inside the jar, otherwise the module layer fails to bind it.
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (var p : descriptor.provides()) {
                if (!FORGE_SERVICES.contains(p.service())) continue;
                for (String provider : p.providers()) {
                    String entry = provider.replace('.', '/') + ".class";
                    boolean present = jf.getEntry(entry) != null;
                    System.out.println("  provider class in jar: " + entry + " = " + present);
                    if (!present) ok = false;
                }
            }
        }

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        if (!ok) System.exit(1);
    }
}
