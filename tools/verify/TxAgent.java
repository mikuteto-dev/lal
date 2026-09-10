import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * Applies LALClassTransformer at class load through Instrumentation, which is how the production
 * launch plugin does it, so BootstrapMain sees exactly the bytecode the client saw.
 *
 * <p>-Dlalskip=a.b.C,... leaves those classes untransformed so a culprit can be isolated.
 */
public final class TxAgent {

    static final Set<String> TARGETS = new HashSet<>();
    static final Set<String> SKIP = new HashSet<>();

    static {
        TARGETS.addAll(Arrays.asList(jp.mikumiku.lal.transformer.LALClassTransformer.targetsForValidation()));
        String skip = System.getProperty("lalskip", "");
        if (!skip.isEmpty()) SKIP.addAll(Arrays.asList(skip.split(",")));
    }

    /**
     * Resolves the common superclass from class file resources instead of Class.forName. Loading
     * classes here re-enters the JVM's class definition and produces "attempted duplicate class
     * definition" (ModLauncher has the same problem and ships an equivalent writer).
     */
    static final class NoLoadWriter extends ClassWriter {
        NoLoadWriter(int flags) { super(flags); }

        @Override
        protected String getCommonSuperClass(String a, String b) {
            if (a.equals(b)) return a;
            if (isAssignable(a, b)) return b;   // a is a subtype of b
            if (isAssignable(b, a)) return a;   // b is a subtype of a
            if (isInterface(a) || isInterface(b)) return "java/lang/Object";
            String c = a;
            for (int guard = 0; guard < 64; guard++) {
                c = superName(c);
                if (c == null) return "java/lang/Object";
                if (isAssignable(c, b)) return c;
            }
            return "java/lang/Object";
        }

        private boolean isAssignable(String from, String to) {
            if (from.equals(to)) return true;
            java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            queue.add(from);
            while (!queue.isEmpty()) {
                String c = queue.poll();
                if (!seen.add(c)) continue;
                if (c.equals(to)) return true;
                String s = superName(c);
                if (s != null) queue.add(s);
                for (String i : interfaces(c)) queue.add(i);
            }
            return false;
        }

        private ClassReader reader(String internalName) {
            try (java.io.InputStream is =
                         TxAgent.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
                return is == null ? null : new ClassReader(is.readAllBytes());
            } catch (Throwable t) {
                return null;
            }
        }

        private String superName(String internalName) {
            ClassReader r = reader(internalName);
            return r == null ? null : r.getSuperName();
        }

        private String[] interfaces(String internalName) {
            ClassReader r = reader(internalName);
            return r == null ? new String[0] : r.getInterfaces();
        }

        private boolean isInterface(String internalName) {
            ClassReader r = reader(internalName);
            return r != null && (r.getAccess() & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
        }
    }

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> beingRedefined,
                                    ProtectionDomain domain, byte[] buffer) {
                if (beingRedefined != null || className == null) return null;
                String name = className.replace('/', '.');
                if (!TARGETS.contains(name) || SKIP.contains(name)) return null;
                try {
                    ClassNode node = new ClassNode();
                    new ClassReader(buffer).accept(node, 0);
                    new jp.mikumiku.lal.transformer.LALClassTransformer().transform(node, null);
                    ClassWriter cw = new NoLoadWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                    node.accept(cw);
                    System.out.println("  [tx] " + name);
                    return cw.toByteArray();
                } catch (Throwable t) {
                    System.out.println("  [tx] FAILED " + name + ": " + t);
                    return null;
                }
            }
        }, true);
    }
}
