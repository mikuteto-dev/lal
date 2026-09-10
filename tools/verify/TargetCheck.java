import java.util.Set;
import cpw.mods.modlauncher.api.ITransformer;

/**
 * ModLauncher matches transformer targets by exact class name, so a typo or a renamed class
 * silently drops a hook. Also checks that LALTransformationService actually exposes the
 * transformer (it used to return an empty list) and that the wired path still produces a
 * verifiable class.
 */
public class TargetCheck {
    public static void main(String[] args) throws Exception {
        boolean ok = true;

        // 1. Wiring: the transformation service must expose exactly the class transformer.
        var service = new jp.mikumiku.lal.transformer.LALTransformationService();
        // The service now runs during launch, before any mod code, so its whole lifecycle must
        // survive being called with the early-launch arguments.
        try {
            System.out.println("service name = " + service.name());
            service.initialize(null);
            service.onLoad(null, java.util.Set.of());
            System.out.println("service lifecycle: no throw");
        } catch (Throwable t) {
            System.out.println("FAIL: transformation service threw during its lifecycle: " + t);
            ok = false;
        }
        var list = service.transformers();
        System.out.println("transformers() size = " + list.size()
                + " -> " + (list.isEmpty() ? "NONE (hook table dead)" : list.get(0).getClass().getSimpleName()));
        if (list.size() != 1 || !(list.get(0) instanceof jp.mikumiku.lal.transformer.LALClassTransformer)) {
            System.out.println("FAIL: transformation service does not expose LALClassTransformer");
            ok = false;
        }

        ITransformer<?> t = list.get(0);

        // 2. Every enumerated target name must resolve; this is the design's failure mode.
        int checked = 0;
        for (String name : jp.mikumiku.lal.transformer.LALClassTransformer.targetsForValidation()) {
            try {
                Class.forName(name, false, TargetCheck.class.getClassLoader());
                checked++;
            } catch (Throwable e) {
                System.out.println("FAIL: target does not resolve: " + name + " (" + e + ")");
                ok = false;
            }
        }
        System.out.println("targets that resolve = " + checked + "/"
                + jp.mikumiku.lal.transformer.LALClassTransformer.targetsForValidation().length);

        // 3. The target set ModLauncher will index must match those names.
        Set<ITransformer.Target> targets = cast(t);
        int matched = 0;
        for (String name : jp.mikumiku.lal.transformer.LALClassTransformer.targetsForValidation()) {
            for (ITransformer.Target target : targets) {
                if (target.getClassName().equals(name)
                        && target.getTargetType() == ITransformer.TargetType.CLASS) {
                    matched++;
                    break;
                }
            }
        }
        System.out.println("targets() entries = " + targets.size() + ", matched CLASS targets = " + matched);
        if (matched != jp.mikumiku.lal.transformer.LALClassTransformer.targetsForValidation().length) {
            System.out.println("FAIL: targets() does not cover the enumerated names");
            ok = false;
        }

        // 4. transform() must still produce a verifiable class through this path.
        org.objectweb.asm.tree.ClassNode cn = Check.buildClass("net/minecraft/test/Dirty");
        cast2(t).transform(cn, null);
        var cw = Check.writer();
        cn.accept(cw);
        Class<?> defined = Check.define("net/minecraft/test/Dirty", cw.toByteArray());
        System.out.println("transform() path => " + defined.getName() + " verified with "
                + defined.getDeclaredMethods().length + " methods");

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        if (!ok) System.exit(1);
    }

    @SuppressWarnings("unchecked")
    static Set<ITransformer.Target> cast(ITransformer<?> t) { return ((ITransformer<Object>) t).targets(); }

    @SuppressWarnings("unchecked")
    static ITransformer<org.objectweb.asm.tree.ClassNode> cast2(ITransformer<?> t) {
        return (ITransformer<org.objectweb.asm.tree.ClassNode>) t;
    }
}
