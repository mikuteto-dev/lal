import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.reflect.*;

/**
 * Verifies the Runtime exit-guard injection: Runtime.halt is native (no Code attribute allowed)
 * and Runtime.exit is static, so its original maxLocals only covers the int parameter while the
 * injected guard uses locals 2 and 3.
 */
public class RuntimeCheck {

    static ClassNode runtimeish() {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = "check/Runtimeish";
        cn.superName = "java/lang/Object";

        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1; init.maxLocals = 1;
        cn.methods.add(init);

        MethodNode exit = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "exit", "(I)V", null, null);
        exit.instructions.add(new InsnNode(Opcodes.RETURN));
        exit.maxStack = 0; exit.maxLocals = 1;              // static (I)V -> only local 0
        cn.methods.add(exit);

        MethodNode halt = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE, "halt", "(I)V", null, null);
        cn.methods.add(halt);
        return cn;
    }

    static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                try { return super.getCommonSuperClass(a, b); } catch (Exception e) { return "java/lang/Object"; }
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    static Class<?> define(byte[] bytes) throws Exception {
        return new ClassLoader(RuntimeCheck.class.getClassLoader()) {
            Class<?> define() { return defineClass("check.Runtimeish", bytes, 0, bytes.length); }
        }.define();
    }

    static Object transformer() throws Exception {
        Class<?> t = Class.forName("jp.mikumiku.lal.agent.LALAgent$LALProtectiveTransformer");
        Constructor<?> c = t.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    static byte[] viaTransformer(Object t, byte[] bytes) throws Exception {
        Method m = t.getClass().getDeclaredMethod("transform", ClassLoader.class, String.class,
                Class.class, java.security.ProtectionDomain.class, byte[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(t, null, "java/lang/Runtime", Runtime.class, null, bytes);
    }

    static MethodNode find(ClassNode cn, String name) {
        for (MethodNode m : cn.methods) if (m.name.equals(name)) return m;
        throw new IllegalStateException(name);
    }

    public static void main(String[] args) throws Exception {
        boolean ok = true;

        // Control A: injecting a guard into the native halt() produces a class the JVM rejects.
        try {
            Object t = transformer();
            Method inject = t.getClass().getDeclaredMethod("injectExitGuard", MethodNode.class);
            inject.setAccessible(true);
            ClassNode cn = runtimeish();
            inject.invoke(t, find(cn, "halt"));
            define(write(cn));
            System.out.println("CONTROL A FAIL: guard in native halt() unexpectedly verified");
            ok = false;
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            System.out.println("control A OK : guard in native halt() rejected (" + c.getClass().getSimpleName() + ")");
        }

        // Fixed: the real transformer skips native halt() and raises maxLocals for exit().
        Object t = transformer();
        byte[] out = viaTransformer(t, write(runtimeish()));
        if (out == null) {
            System.out.println("FAIL: transformRuntime produced no change");
            ok = false;
        } else {
            ClassNode cn = new ClassNode();
            new ClassReader(out).accept(cn, 0);
            MethodNode exit = find(cn, "exit");
            MethodNode halt = find(cn, "halt");
            System.out.println("fixed        : exit guard injected=" + (exit.instructions.size() > 1)
                    + " maxLocals=" + exit.maxLocals
                    + ", halt native untouched=" + (halt.instructions.size() == 0));
            if (halt.instructions.size() != 0) { System.out.println("FAIL: native halt() was modified"); ok = false; }
            Class<?> defined = define(out);
            System.out.println("fixed        : defined and verified " + defined.getName()
                    + " (" + defined.getDeclaredMethods().length + " methods)");
            if ((halt.access & Opcodes.ACC_NATIVE) == 0 || exit.instructions.size() <= 1 || exit.maxLocals < 4) {
                System.out.println("FAIL: guard not applied as expected");
                ok = false;
            }
        }

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        if (!ok) System.exit(1);
    }
}
