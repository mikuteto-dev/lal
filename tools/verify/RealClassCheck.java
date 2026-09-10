import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * Runs the wired transformer over the *real* Minecraft class files listed in the target set.
 *
 * <p>The synthetic checks prove the injected shapes are valid; this proves the same thing against
 * the actual bytecode and class hierarchy, which is what a VerifyError in-game would come from.
 * It also catches a target name that does not correspond to any class resource.
 */
public class RealClassCheck {

    public static void main(String[] args) throws Exception {
        boolean ok = true;
        int present = 0;
        int modified = 0;

        var transformer = (cpw.mods.modlauncher.api.ITransformer<ClassNode>)
                new jp.mikumiku.lal.transformer.LALClassTransformer();

        for (String name : jp.mikumiku.lal.transformer.LALClassTransformer.targetsForValidation()) {
            String resource = name.replace('.', '/') + ".class";
            byte[] bytes;
            try (InputStream is = RealClassCheck.class.getClassLoader().getResourceAsStream(resource)) {
                if (is == null) {
                    System.out.println("MISSING  " + name + "  (no class resource)");
                    ok = false;
                    continue;
                }
                bytes = is.readAllBytes();
            }
            present++;

            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            int before = node.methods.size();
            transformer.transform(node, null);

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected String getCommonSuperClass(String a, String b) {
                    try { return super.getCommonSuperClass(a, b); } catch (Throwable t) { return "java/lang/Object"; }
                }
            };
            byte[] out;
            try {
                node.accept(cw);
                out = cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("WRITE FAIL " + name + " : " + t);
                ok = false;
                continue;
            }

            // Defining runs the verifier, which is what rejects bad injected frames.
            try {
                define(name, out);
            } catch (Throwable t) {
                System.out.println("VERIFY FAIL " + name + " : " + t);
                ok = false;
                continue;
            }
            System.out.println("ok       " + name + " (methods " + before + " -> " + node.methods.size() + ")");
            modified++;
        }

        System.out.println("targets present = " + present + ", transformed+verified = " + modified);
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        if (!ok) System.exit(1);
    }

    static void define(String binaryName, byte[] bytes) {
        new ClassLoader(RealClassCheck.class.getClassLoader()) {
            Class<?> go() { return defineClass(binaryName, bytes, 0, bytes.length); }
        }.go();
    }
}
