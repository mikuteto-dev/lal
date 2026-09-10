package jp.mikumiku.lal.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jp.mikumiku.lal.transformer.LALTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class LALAgent {
    private static final Set<String> PROTECTED_CLASSES = ConcurrentHashMap.newKeySet();
    private static volatile ClassFileTransformer lalTransformer;

    private static final Object[] _0x = new Object[4];
    private static volatile java.lang.invoke.VarHandle[] hsHandles;
    private static volatile Class<?> bsClassRef;
    private static volatile java.lang.reflect.Field[] bsFieldRefs;
    private static final ConcurrentHashMap<String, byte[]> LAL_CLASS_BYTECODE = new ConcurrentHashMap<>();

    public static void premain(String args, Instrumentation inst) {
        initAgent(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        initAgent(inst);
    }

            /** For a handle obtained by self-attach, on the mod thread. */
    public static void initInstrumentation(Instrumentation inst) {
        initAgent(inst);
    }

    public static boolean isProtected(String className) {
        return PROTECTED_CLASSES.contains(className);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean AGENT_INITIALIZED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void initAgent(Instrumentation inst) {
        // premain and agentmain can both run; the bootstrap append is irreversible.
        LALAgentBridge.setInstrumentation(inst);
        if (!AGENT_INITIALIZED.compareAndSet(false, true)) return;
        captureOriginalBytecodes(inst);
        lalTransformer = new LALProtectiveTransformer();
        inst.addTransformer(lalTransformer, true);
        tryInstallHiddenTransformer(inst);
        initHiddenBackupSets();
        initHiddenClassStorage();
        storeInstrumentationInHidden(inst);
        initBootstrapStorage(inst);
        tryLoadNative();
    }

    private static void captureOriginalBytecodes(Instrumentation inst) {
        try {
            ClassLoader cl = LALAgent.class.getClassLoader();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                try {
                    String name = c.getName();
                    if (name.startsWith("jp.mikumiku.lal.")) {
                        String internalName = name.replace('.', '/');
                        try (java.io.InputStream is = cl.getResourceAsStream(internalName + ".class")) {
                            if (is != null) {
                                LAL_CLASS_BYTECODE.put(internalName, is.readAllBytes());
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            ClassLoader cl = LALAgent.class.getClassLoader();
            String selfName = LALAgent.class.getName().replace('.', '/');
            if (!LAL_CLASS_BYTECODE.containsKey(selfName)) {
                try (java.io.InputStream is = cl.getResourceAsStream(selfName + ".class")) {
                    if (is != null) LAL_CLASS_BYTECODE.put(selfName, is.readAllBytes());
                }
            }
            String bridgeName = LALAgentBridge.class.getName().replace('.', '/');
            if (!LAL_CLASS_BYTECODE.containsKey(bridgeName)) {
                try (java.io.InputStream is = cl.getResourceAsStream(bridgeName + ".class")) {
                    if (is != null) LAL_CLASS_BYTECODE.put(bridgeName, is.readAllBytes());
                }
            }
            String transformerName = selfName + "$LALProtectiveTransformer";
            if (!LAL_CLASS_BYTECODE.containsKey(transformerName)) {
                try (java.io.InputStream is = cl.getResourceAsStream(transformerName + ".class")) {
                    if (is != null) LAL_CLASS_BYTECODE.put(transformerName, is.readAllBytes());
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void initHiddenBackupSets() {
        _0x[0] = ConcurrentHashMap.newKeySet();
        _0x[1] = ConcurrentHashMap.newKeySet();
        _0x[2] = Long.valueOf(0L);
        _0x[3] = Long.valueOf(System.nanoTime());
    }

    private static byte[] generateHiddenStorageBytes() {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = "jp/mikumiku/lal/agent/HS";
        cn.superName = "java/lang/Object";
        for (int i = 0; i < 5; i++) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                    "s" + i, "Ljava/lang/Object;", null, null));
        }
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1;
        init.maxLocals = 1;
        cn.methods.add(init);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static void initHiddenClassStorage() {
        try {
            byte[] bytes = generateHiddenStorageBytes();
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(LALAgent.class, MethodHandles.lookup());
            MethodHandles.Lookup hl = lookup.defineHiddenClass(bytes, true, MethodHandles.Lookup.ClassOption.STRONG);
            Class<?> hc = hl.lookupClass();
            java.lang.invoke.VarHandle[] handles = new java.lang.invoke.VarHandle[5];
            for (int i = 0; i < 5; i++) {
                handles[i] = hl.findStaticVarHandle(hc, "s" + i, Object.class);
            }
            handles[0].set(ConcurrentHashMap.newKeySet());
            handles[1].set(ConcurrentHashMap.newKeySet());
            handles[2].set(ConcurrentHashMap.newKeySet());
            hsHandles = handles;
        } catch (Throwable ignored) {}
    }

    public static void storeInstrumentationInHidden(Instrumentation inst) {
        try {
            java.lang.invoke.VarHandle[] h = hsHandles;
            if (h != null && h[3] != null) {
                h[3].set(inst);
            }
        } catch (Throwable ignored) {}
        try {
            System.getProperties().put("\0lal\0i", inst);
        } catch (Throwable ignored) {}
    }

    public static Instrumentation recoverInstrumentationFromHidden() {
        try {
            java.lang.invoke.VarHandle[] h = hsHandles;
            if (h != null && h[3] != null) {
                Object val = h[3].get();
                if (val instanceof Instrumentation inst && LALAgentBridge.isValidInstrumentation(inst)) return inst;
            }
        } catch (Throwable ignored) {}
        try {
            Object val = System.getProperties().get("\0lal\0i");
            if (val instanceof Instrumentation inst && LALAgentBridge.isValidInstrumentation(inst)) return inst;
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    public static void syncToHiddenClassStorage(Set<UUID> killSet, Set<UUID> immortalSet, Set<UUID> deadConfirmed) {
        try {
            java.lang.invoke.VarHandle[] h = hsHandles;
            if (h == null) return;
            Set<UUID> ks = (Set<UUID>) h[0].get();
            Set<UUID> is = (Set<UUID>) h[1].get();
            Set<UUID> dc = (Set<UUID>) h[2].get();
            if (ks != null) { ks.clear(); ks.addAll(killSet); }
            if (is != null) { is.clear(); is.addAll(immortalSet); }
            if (dc != null) { dc.clear(); dc.addAll(deadConfirmed); }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    public static Set<UUID> getHiddenClassKillSet() {
        try {
            java.lang.invoke.VarHandle[] h = hsHandles;
            if (h != null) return (Set<UUID>) h[0].get();
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Set<UUID> getHiddenClassImmortalSet() {
        try {
            java.lang.invoke.VarHandle[] h = hsHandles;
            if (h != null) return (Set<UUID>) h[1].get();
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Set<UUID> getHiddenClassDeadConfirmed() {
        try {
            java.lang.invoke.VarHandle[] h = hsHandles;
            if (h != null) return (Set<UUID>) h[2].get();
        } catch (Throwable ignored) {}
        return null;
    }

    private static volatile boolean bootstrapAvailable = false;

    private static volatile String bsInternalName;
    private static final String[] BS_FIELD_NAMES = {"f0", "f1", "f2", "f3"};

    private static byte[] generateBootstrapStateBytes() {
        String randomName = "S" + Long.toHexString(System.nanoTime()) + Integer.toHexString((int)(Math.random() * 0xFFFF));
        bsInternalName = randomName;
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V17;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = randomName;
        cn.superName = "java/lang/Object";
        for (String name : BS_FIELD_NAMES) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                    name, "Ljava/lang/Object;", null, null));
        }
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1;
        init.maxLocals = 1;
        cn.methods.add(init);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static void initBootstrapStorage(Instrumentation inst) {
        try {
            byte[] stateBytes = generateBootstrapStateBytes();
            java.io.File tempJar = java.io.File.createTempFile("lal-bs-", ".jar");
            tempJar.deleteOnExit();
            try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                    new java.io.FileOutputStream(tempJar))) {
                java.util.jar.JarEntry entry = new java.util.jar.JarEntry(bsInternalName + ".class");
                jos.putNextEntry(entry);
                jos.write(stateBytes);
                jos.closeEntry();
            }
            inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(tempJar));
            Class<?> cls = Class.forName(bsInternalName, true, null);
            java.lang.reflect.Field[] fields = new java.lang.reflect.Field[4];
            for (int i = 0; i < BS_FIELD_NAMES.length; i++) {
                fields[i] = cls.getDeclaredField(BS_FIELD_NAMES[i]);
                fields[i].setAccessible(true);
            }
            fields[0].set(null, inst);
            fields[1].set(null, ConcurrentHashMap.newKeySet());
            fields[2].set(null, ConcurrentHashMap.newKeySet());
            fields[3].set(null, ConcurrentHashMap.newKeySet());
            bsClassRef = cls;
            bsFieldRefs = fields;
            bootstrapAvailable = true;
            try {
                java.lang.invoke.VarHandle[] h = hsHandles;
                if (h != null && h.length > 4 && h[4] != null) {
                    h[4].set(cls);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static Class<?> resolveBsClass() {
        Class<?> cls = bsClassRef;
        if (cls != null) return cls;
        try {
            java.lang.invoke.VarHandle[] h = hsHandles;
            if (h != null && h.length > 4 && h[4] != null) {
                Object val = h[4].get();
                if (val instanceof Class<?> c) {
                    bsClassRef = c;
                    return c;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static java.lang.reflect.Field resolveBsField(int idx) {
        java.lang.reflect.Field[] fields = bsFieldRefs;
        if (fields != null && idx < fields.length && fields[idx] != null) return fields[idx];
        try {
            Class<?> cls = resolveBsClass();
            if (cls == null) return null;
            java.lang.reflect.Field f = cls.getDeclaredField(BS_FIELD_NAMES[idx]);
            f.setAccessible(true);
            if (fields == null) fields = new java.lang.reflect.Field[4];
            fields[idx] = f;
            bsFieldRefs = fields;
            return f;
        } catch (Throwable ignored) {}
        return null;
    }

    public static Instrumentation recoverFromBootstrap() {
        if (!bootstrapAvailable) return null;
        try {
            java.lang.reflect.Field f = resolveBsField(0);
            if (f == null) return null;
            Object val = f.get(null);
            if (val instanceof Instrumentation inst && LALAgentBridge.isValidInstrumentation(inst)) return inst;
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    public static void syncToBootstrap(Set<UUID> killSet, Set<UUID> immortalSet, Set<UUID> deadConfirmed) {
        if (!bootstrapAvailable) return;
        try {
            java.lang.reflect.Field fKs = resolveBsField(1);
            java.lang.reflect.Field fIs = resolveBsField(2);
            java.lang.reflect.Field fDc = resolveBsField(3);
            if (fKs != null) { Set<UUID> ks = (Set<UUID>) fKs.get(null); if (ks != null) { ks.clear(); ks.addAll(killSet); } }
            if (fIs != null) { Set<UUID> is = (Set<UUID>) fIs.get(null); if (is != null) { is.clear(); is.addAll(immortalSet); } }
            if (fDc != null) { Set<UUID> dc = (Set<UUID>) fDc.get(null); if (dc != null) { dc.clear(); dc.addAll(deadConfirmed); } }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    public static Set<UUID> getBootstrapKillSet() {
        if (!bootstrapAvailable) return null;
        try {
            java.lang.reflect.Field f = resolveBsField(1);
            if (f != null) return (Set<UUID>) f.get(null);
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Set<UUID> getBootstrapImmortalSet() {
        if (!bootstrapAvailable) return null;
        try {
            java.lang.reflect.Field f = resolveBsField(2);
            if (f != null) return (Set<UUID>) f.get(null);
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    public static void syncHiddenBackups(Set<UUID> killSet, Set<UUID> immortalSet) {
        try {
            Set<UUID> ks = (Set<UUID>) _0x[0];
            Set<UUID> is = (Set<UUID>) _0x[1];
            if (ks != null) {
                ks.clear();
                ks.addAll(killSet);
            }
            if (is != null) {
                is.clear();
                is.addAll(immortalSet);
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    public static Set<UUID> getHiddenKillSetBackup() {
        try { return (Set<UUID>) _0x[0]; } catch (Throwable t) { return null; }
    }

    @SuppressWarnings("unchecked")
    public static Set<UUID> getHiddenImmortalSetBackup() {
        try { return (Set<UUID>) _0x[1]; } catch (Throwable t) { return null; }
    }

    public static void monitorClassScanning() {
        try {
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null) return;
            long count = ((Long) _0x[2]) + 1;
            _0x[2] = Long.valueOf(count);
            long lastTime = (Long) _0x[3];
            long now = System.nanoTime();
            long elapsed = now - lastTime;
            if (elapsed > 0 && elapsed < 1_000_000_000L && count > 10) {
                retransformTargetClasses();
                _0x[2] = Long.valueOf(0L);
                _0x[3] = Long.valueOf(now);
            }
            if (elapsed >= 10_000_000_000L) {
                _0x[2] = Long.valueOf(0L);
                _0x[3] = Long.valueOf(now);
            }
        } catch (Throwable ignored) {}
    }

    private static void tryLoadNative() {
        try {
            jp.mikumiku.lal.util.NativeLoader.ensureLoaded();
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

    /**
     * Retransforming these deoptimises every compiled method of the class, so the callers - all
     * best-effort self-healing - are spaced.
     */
    private static final long MIN_RETRANSFORM_INTERVAL_MS = 5000L;
    /**
     * Redefining Entity/LivingEntity/Player/ServerPlayer/ServerLevel while another thread is
     * inside Bootstrap.bootStrap() breaks class loading - the client dies with
     * ClassNotFoundException for a vanilla class reached from a static initializer. Nothing needs
     * repairing before the game is up, so retransformation is held off for this long.
     */
    private static final long STARTUP_GRACE_MS = 60_000L;
    private static final long STARTED_AT_MS = System.currentTimeMillis();
    private static final java.util.concurrent.atomic.AtomicLong lastRetransformMs =
            new java.util.concurrent.atomic.AtomicLong(0L);

    private static boolean claimRetransformSlot() {
        long now = System.currentTimeMillis();
        if (now - STARTED_AT_MS < STARTUP_GRACE_MS) return false;
        long last = lastRetransformMs.get();
        if (now - last < MIN_RETRANSFORM_INTERVAL_MS) return false;
        return lastRetransformMs.compareAndSet(last, now);
    }

    public static void retransformTargetClasses() {
        if (!claimRetransformSlot()) return;
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

        try {
            inst.retransformClasses(Runtime.class);
        } catch (Throwable ignored) {}

        try {
            Class<?> modListClass = Class.forName("net.minecraftforge.fml.ModList");
            inst.retransformClasses(modListClass);
        } catch (Throwable ignored) {}
    }

    private static class LALProtectiveTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined == null) return null;
            if (className == null) return null;
            if ("java/lang/Runtime".equals(className)) {
                return transformRuntime(classfileBuffer);
            }
            if (className.startsWith("jp/mikumiku/lal/")) {
                byte[] original = LAL_CLASS_BYTECODE.get(className);
                if (original != null) return original;
                try {
                    ClassLoader cl = LALAgent.class.getClassLoader();
                    if (cl != null) {
                        try (java.io.InputStream is = cl.getResourceAsStream(className + ".class")) {
                            if (is != null) {
                                byte[] b = is.readAllBytes();
                                LAL_CLASS_BYTECODE.put(className, b);
                                return b;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                return classfileBuffer;
            }
            if (!PROTECTED_CLASSES.contains(className)) return null;
            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassNode classNode = new ClassNode();
                cr.accept(classNode, 0);

                boolean alreadyHooked = false;
                for (MethodNode mn : classNode.methods) {
                    if (mn.instructions == null) continue;
                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof MethodInsnNode min) {
                            if ("jp/mikumiku/lal/transformer/EntityMethodHooks".equals(min.owner)) {
                                alreadyHooked = true;
                                break;
                            }
                        }
                        if (alreadyHooked) break;
                        if (insn.getOpcode() != -1) break;
                    }
                    if (alreadyHooked) break;
                }
                if (alreadyHooked) {
                    return null;
                }

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
                    byte[] result = cw.toByteArray();
                    return result;
                }
            } catch (Exception ignored) {}
            return null;
        }

        private byte[] transformRuntime(byte[] classfileBuffer) {
            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);
                boolean modified = false;
                for (MethodNode mn : cn.methods) {
                    if (("exit".equals(mn.name) || "halt".equals(mn.name))
                            && "(I)V".equals(mn.desc)) {
                        // JVMS 4.7.3 forbids a Code attribute on a native method, and emitting one
                        // aborted the whole Runtime retransform.
                        if ((mn.access & Opcodes.ACC_NATIVE) != 0) continue;
                        injectExitGuard(mn);
                        modified = true;
                    }
                }
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
                    cn.accept(cw);
                    return cw.toByteArray();
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private void injectExitGuard(MethodNode mn) {
            InsnList inject = new InsnList();
            LabelNode tryStart = new LabelNode();
            LabelNode tryEnd = new LabelNode();
            LabelNode catchHandler = new LabelNode();
            LabelNode allowLabel = new LabelNode();
            LabelNode loopLabel = new LabelNode();
            LabelNode blockLabel = new LabelNode();

            inject.add(tryStart);
            inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false));
            inject.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Thread", "getStackTrace", "()[Ljava/lang/StackTraceElement;", false));
            inject.add(new VarInsnNode(Opcodes.ASTORE, 2));
            inject.add(new InsnNode(Opcodes.ICONST_0));
            inject.add(new VarInsnNode(Opcodes.ISTORE, 3));
            inject.add(loopLabel);
            inject.add(new VarInsnNode(Opcodes.ILOAD, 3));
            inject.add(new VarInsnNode(Opcodes.ALOAD, 2));
            inject.add(new InsnNode(Opcodes.ARRAYLENGTH));
            inject.add(new JumpInsnNode(Opcodes.IF_ICMPGE, blockLabel));
            inject.add(new VarInsnNode(Opcodes.ALOAD, 2));
            inject.add(new VarInsnNode(Opcodes.ILOAD, 3));
            inject.add(new InsnNode(Opcodes.AALOAD));
            inject.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/StackTraceElement", "getClassName", "()Ljava/lang/String;", false));
            inject.add(new LdcInsnNode("jp.mikumiku.lal."));
            inject.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false));
            inject.add(new JumpInsnNode(Opcodes.IFNE, allowLabel));
            inject.add(new IincInsnNode(3, 1));
            inject.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));
            inject.add(blockLabel);
            inject.add(new InsnNode(Opcodes.RETURN));
            inject.add(tryEnd);
            inject.add(new JumpInsnNode(Opcodes.GOTO, allowLabel));
            inject.add(catchHandler);
            inject.add(new InsnNode(Opcodes.POP));
            inject.add(allowLabel);

            mn.instructions.insert(inject);
            mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchHandler, "java/lang/Throwable"));
        }
    }

    public static void reRegisterTransformer() {
        try {
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null || lalTransformer == null) return;
            inst.removeTransformer(lalTransformer);
            inst.addTransformer(lalTransformer, true);
        } catch (Throwable ignored) {}
    }
}
