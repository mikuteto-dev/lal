package jp.mikumiku.lal.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jp.mikumiku.lal.transformer.LALTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

public class LALAgent {
    private static final Set<String> PROTECTED_CLASSES = ConcurrentHashMap.newKeySet();
    private static volatile ClassFileTransformer lalTransformer;

    public static void premain(String args, Instrumentation inst) {
        initAgent(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        initAgent(inst);
    }

    public static boolean isProtected(String className) {
        return PROTECTED_CLASSES.contains(className);
    }

    private static void initAgent(Instrumentation inst) {
        LALAgentBridge.setInstrumentation(inst);
        lalTransformer = new LALProtectiveTransformer();
        inst.addTransformer(lalTransformer, true);
        tryInstallHiddenTransformer(inst);
        tryLoadNative();
    }

    private static void tryLoadNative() {
        try {
            java.io.InputStream is = LALAgent.class.getResourceAsStream("/native/lal.dll");
            if (is == null) return;
            java.io.File tmp = java.io.File.createTempFile("lal_native_", ".dll");
            tmp.deleteOnExit();
            try (java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) os.write(buf, 0, len);
            }
            is.close();
            System.load(tmp.getAbsolutePath());
        } catch (Throwable ignored) {}
    }

    private static void tryInstallHiddenTransformer(Instrumentation inst) {
        try {
            String resourceName = LALAgent.class.getName().replace('.', '/') + "$LALProtectiveTransformer.class";
            byte[] bytes;
            try (java.io.InputStream is = LALAgent.class.getClassLoader().getResourceAsStream(resourceName)) {
                if (is == null) return;
                bytes = is.readAllBytes();
            }
            java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                    LALAgent.class, java.lang.invoke.MethodHandles.lookup());
            Class<?> hiddenClass = lookup.defineHiddenClass(bytes, true,
                    java.lang.invoke.MethodHandles.Lookup.ClassOption.STRONG).lookupClass();
            ClassFileTransformer hiddenTransformer = (ClassFileTransformer)
                    hiddenClass.getDeclaredConstructors()[0].newInstance();
            inst.addTransformer(hiddenTransformer, true);
        } catch (Throwable ignored) {}
    }

    public static void markProtected(String internalName) {
        PROTECTED_CLASSES.add(internalName);
    }

    public static void retransformTargetClasses() {
        Instrumentation inst = LALAgentBridge.getInstrumentation();
        if (inst == null) return;

        try {
            if (lalTransformer != null) {
                inst.removeTransformer(lalTransformer);
                inst.addTransformer(lalTransformer, true);
            }
        } catch (Throwable ignored) {}

        try {
            Class<?>[] targets = {
                    net.minecraft.world.entity.Entity.class,
                    net.minecraft.world.entity.LivingEntity.class,
                    net.minecraft.world.entity.player.Player.class,
                    net.minecraft.server.level.ServerPlayer.class,
                    net.minecraft.server.level.ServerLevel.class
            };
            for (Class<?> c : targets) {
                try { inst.retransformClasses(c); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static class LALProtectiveTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined == null) return null;
            if (className == null || !PROTECTED_CLASSES.contains(className)) return null;
            if (className.startsWith("jp/mikumiku/lal/")) return null;
            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassNode classNode = new ClassNode();
                cr.accept(classNode, 0);

                boolean modified = LALTransformer.transform(classNode);
                if (modified) {
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                        @Override
                        protected String getCommonSuperClass(String type1, String type2) {
                            try {
                                return super.getCommonSuperClass(type1, type2);
                            } catch (Exception e) {
                                return "java/lang/Object";
                            }
                        }
                    };
                    classNode.accept(cw);
                    return cw.toByteArray();
                }
            } catch (Exception ignored) {}
            return null;
        }

    }
}
