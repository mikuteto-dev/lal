import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Runnable check for LALTransformer's hook injection: operand derivation and owner gating. */
public class Check {

    static ClassNode buildClass(String internalName) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = internalName;
        cn.superName = "java/lang/Object";

        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1;
        init.maxLocals = 1;
        cn.methods.add(init);

        // Name+descriptor pairs from the hook table. A wrong operand push is a VerifyError.
        addMethod(cn, "setDirty", "(Z)V", 1);
        addMethod(cn, "kill", "()V", 0);
        addMethod(cn, "baseTick", "()V", 0);
        addMethod(cn, "tick", "()V", 0);
        addMethod(cn, "setPosRaw", "(DDD)V", 3);
        addMethod(cn, "setArrowCount", "(I)V", 1);
        addMethod(cn, "heal", "(F)V", 1);
        addMethod(cn, "setPose", "(Lnet/minecraft/world/entity/Pose;)V", 1);
        addMethod(cn, "setRemoved", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", 1);
        addMethod(cn, "setLevelCallback", "(Lnet/minecraft/world/level/entity/EntityInLevelCallback;)V", 1);
        return cn;
    }

    static void addMethod(ClassNode cn, String name, String desc, int argSlots) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.maxStack = 0;
        m.maxLocals = 1 + argSlots;
        cn.methods.add(m);
    }

    static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                try { return super.getCommonSuperClass(a, b); } catch (Exception e) { return "java/lang/Object"; }
            }
        };
    }

    static Class<?> define(String internalName, byte[] bytes) throws Exception {
        String binaryName = internalName.replace('/', '.');
        return new ClassLoader(Check.class.getClassLoader()) {
            Class<?> define() { return defineClass(binaryName, bytes, 0, bytes.length); }
        }.define();
    }

    static final String HOOKS = "jp/mikumiku/lal/transformer/EntityMethodHooks";

    /** The pre-fix prologue: only ALOAD 0 for a hook whose descriptor wants (Object, Z). */
    static byte[] buggySetDirty(String internalName) {
        ClassNode cn = buildClass(internalName);
        for (MethodNode m : cn.methods) {
            if (!"setDirty".equals(m.name)) continue;
            InsnList patch = new InsnList();
            patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    "shouldBlockDataItemSetDirty", "(Ljava/lang/Object;Z)Z", false));
            LabelNode skip = new LabelNode(new Label());
            patch.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            patch.add(new InsnNode(Opcodes.RETURN));
            patch.add(skip);
            m.instructions.insertBefore(m.instructions.getFirst(), patch);
            m.maxStack += 8;
        }
        ClassWriter cw = writer();
        cn.accept(cw);
        return cw.toByteArray();
    }

    static int countHooks(byte[] b) {
        int n = 0; String s = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
        int i = -1; while ((i = s.indexOf("EntityMethodHooks", i + 1)) >= 0) n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        boolean ok = true;
        var phaseBefore = cpw.mods.modlauncher.serviceapi.ILaunchPluginService.Phase.BEFORE;
        var phaseAfter = cpw.mods.modlauncher.serviceapi.ILaunchPluginService.Phase.AFTER;

        // 1. Control: the pre-fix prologue must fail, proving this check can see the defect.
        try {
            define("net/minecraft/test/Dirty", buggySetDirty("net/minecraft/test/Dirty"));
            System.out.println("CONTROL FAIL: buggy prologue unexpectedly verified");
            ok = false;
        } catch (VerifyError e) {
            System.out.println("control OK   : buggy prologue rejected at load (VerifyError)");
        } catch (Throwable t) {
            System.out.println("control OK   : buggy prologue rejected while writing ("
                    + t.getClass().getSimpleName() + ")");
        }

        // 2. Vanilla owner: hooks must be injected and the result must verify. Driven through the
        //    same two-phase flow LALPlugin uses, so the per-class scan cache is exercised too.
        ClassNode cn = buildClass("net/minecraft/test/Dirty");
        boolean before = jp.mikumiku.lal.transformer.LALTransformer.transform(cn, phaseBefore);
        boolean after = jp.mikumiku.lal.transformer.LALTransformer.transform(cn, phaseAfter);
        ClassWriter cw = writer();
        cn.accept(cw);
        Class<?> defined = define("net/minecraft/test/Dirty", cw.toByteArray());
        System.out.println("vanilla owner: BEFORE=" + before + " AFTER=" + after
                + ", defined+verified with " + defined.getDeclaredMethods().length + " methods");
        if (!before) { System.out.println("FAIL: vanilla class was not hooked"); ok = false; }

        // 3. Mod owner with identical name/descriptor pairs must NOT be rewritten with entity
        //    semantics (replaceGetHealth returns 20.0f for a non-LivingEntity receiver).
        ClassNode mod = buildClass("com/example/Dirty");
        boolean modBefore = jp.mikumiku.lal.transformer.LALTransformer.transform(mod, phaseBefore);
        boolean modAfter = jp.mikumiku.lal.transformer.LALTransformer.transform(mod, phaseAfter);
        boolean modModified = modBefore || modAfter;
        System.out.println("mod owner    : BEFORE=" + modBefore + " AFTER=" + modAfter);
        for (MethodNode m : mod.methods) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode mi && HOOKS.equals(mi.owner)) {
                    System.out.println("  injected into " + m.name + m.desc + " -> " + mi.name + mi.desc);
                }
            }
        }
        ClassWriter mw = writer();
        mod.accept(mw);
        System.out.println("  hook refs in result = " + countHooks(mw.toByteArray()));
        Class<?> modDefined = define("com/example/Dirty", mw.toByteArray());
        System.out.println("mod owner    : modified=" + modModified
                + ", defined+verified with " + modDefined.getDeclaredMethods().length + " methods");
        if (modModified) { System.out.println("FAIL: mod class was rewritten with entity hooks"); ok = false; }

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        if (!ok) System.exit(1);
    }
}
