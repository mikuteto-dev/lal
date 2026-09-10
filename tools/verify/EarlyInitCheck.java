import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Fails if a method the transformer injects into can be reached from another class's static
 * initializer.
 *
 * <p>Running an injected hook during someone else's &lt;clinit&gt; loads the hook class at that
 * moment, during Bootstrap. That is how the client died with ClassNotFoundException: Player:
 * Entity.&lt;clinit&gt; -> SynchedEntityData.defineId, where defineId's unguarded
 * Thread.getStackTrace()/Class.forName made processSelfDefence inject into it. The invariant is
 * therefore "no injected method is callable from a &lt;clinit&gt;", and this pins it instead of
 * leaving it to be rediscovered.
 */
public class EarlyInitCheck {

    static final String HOOKS = "jp/mikumiku/lal/transformer/EntityMethodHooks";

    public static void main(String[] args) throws Exception {
        List<String> targets = new ArrayList<>(
                Arrays.asList(jp.mikumiku.lal.transformer.LALClassTransformer.targetsForValidation()));
        targets.add("net.minecraft.client.renderer.LevelRenderer");
        targets.add("net.minecraft.client.renderer.GameRenderer");
        // Control: -Dlalextra=... adds back a class that was removed as unsafe, so a PASS is known
        // to mean something.
        String extra = System.getProperty("lalextra", "");
        if (!extra.isEmpty()) targets.addAll(Arrays.asList(extra.split(",")));

        try (ZipFile zip = new ZipFile(args[0])) {
            // Which methods does the transformer actually rewrite?
            Set<String> injected = new TreeSet<>();
            for (String target : targets) {
                ZipEntry entry = zip.getEntry(target.replace('.', '/') + ".class");
                if (entry == null) continue;
                ClassNode cn = new ClassNode();
                try (var is = zip.getInputStream(entry)) {
                    new ClassReader(is.readAllBytes()).accept(cn, 0);
                }
                new jp.mikumiku.lal.transformer.LALClassTransformer().transform(cn, null);
                for (MethodNode m : cn.methods) {
                    if (m.instructions == null) continue;
                    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof MethodInsnNode mi && HOOKS.equals(mi.owner)) {
                            injected.add(target.replace('.', '/') + "." + m.name + m.desc);
                            break;
                        }
                    }
                }
            }
            System.out.println("injected methods: " + injected.size());

            Map<String, String> reachable = new TreeMap<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                ClassNode cn = new ClassNode();
                try (var is = zip.getInputStream(e)) {
                    new ClassReader(is.readAllBytes()).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (Throwable t) {
                    continue;
                }
                for (MethodNode m : cn.methods) {
                    if (!"<clinit>".equals(m.name) || m.instructions == null) continue;
                    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (!(insn instanceof MethodInsnNode mi)) continue;
                        if (mi.getOpcode() == Opcodes.INVOKESPECIAL) continue;
                        String key = mi.owner + "." + mi.name + mi.desc;
                        if (injected.contains(key)) {
                            reachable.putIfAbsent(key, cn.name + ".<clinit>");
                        }
                    }
                }
            }

            if (reachable.isEmpty()) {
                System.out.println("no injected method is reachable from a <clinit>");
                System.out.println("RESULT: PASS");
                return;
            }
            for (Map.Entry<String, String> e : reachable.entrySet()) {
                System.out.println("REACHABLE FROM <clinit>: " + e.getKey() + "  <- " + e.getValue());
            }
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
    }
}
