package jp.mikumiku.lal.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import jp.mikumiku.lal.agent.LALAgentBridge;

public class LALAgentLoader {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("lal");

    /**
     * Left in the working directory so -javaagent:lal-agent.jar can activate the agent on the next
     * launch: the JDK denies self-attach by default and no mod can add JVM startup arguments.
     */
    public static final File DEPLOYED_AGENT_JAR = new File("lal-agent.jar");

    private static boolean attempted = false;

    public LALAgentLoader() {
        super();
    }

    public static void load() {
        if (attempted) {
            return;
        }
        attempted = true;
        if (LALAgentBridge.isAgentReady()) {
            return;
        }

        // Denied by default since JDK 9, and the decision is taken when the attach
        // implementation initialises, so this must precede any attach-class load.
        try { System.setProperty("jdk.attach.allowAttachSelf", "true"); } catch (Throwable ignored) {}

        try {
            File agentJar = LALAgentLoader.createAgentJar();
            LALAgentLoader.attachAgent(agentJar);
        }
        catch (Throwable e) {
        }

        // Initialised here rather than on the Attach Listener thread.
        try {
            Object captured = System.getProperties().get(LALBootstrap.INSTRUMENTATION_KEY);
            if (captured instanceof java.lang.instrument.Instrumentation instrumentation) {
                LALAgent.initInstrumentation(instrumentation);
            }
        }
        catch (Throwable e) {
        }

        reportStatus();
    }

    /**
     * One line either way: with every failure swallowed, a dead agent layer looked identical to a
     * working one.
     */
    private static void reportStatus() {
        try {
            if (LALAgentBridge.isAgentReady()) {
                LOGGER.info("[LAL] java agent attached: retransform self-heal, class scans and bootstrap storage are active");
            } else {
                LOGGER.warn("[LAL] java agent NOT attached - retransform self-heal, class scans and bootstrap storage are inactive. "
                        + "JVM self-attach requires -Djdk.attach.allowAttachSelf=true, or launch with -javaagent:{}",
                        LALAgentLoader.DEPLOYED_AGENT_JAR);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * The Agent-Class must be inside the jar: the Attach API loads it with the system class loader,
     * which cannot see this mod's classes.
     */
    private static File createAgentJar() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "lal-agent");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File agentJar = new File(tempDir, "lal-agent.jar");
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Agent-Class", LALBootstrap.class.getName());
        attrs.putValue("Can-Retransform-Classes", "true");
        attrs.putValue("Can-Redefine-Classes", "true");

        String entryName = LALBootstrap.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(agentJar), manifest)) {
            try (java.io.InputStream is = LALBootstrap.class.getClassLoader().getResourceAsStream(entryName)) {
                if (is == null) {
                    throw new java.io.IOException("bootstrap agent class not found: " + entryName);
                }
                jos.putNextEntry(new java.util.jar.JarEntry(entryName));
                is.transferTo(jos);
                jos.closeEntry();
            }
        }
        deployAgentJar(agentJar);
        return agentJar;
    }

            /** Best effort, so -javaagent:lal-agent.jar has a stable path. */
    private static void deployAgentJar(File built) {
        try {
            if (built.getAbsoluteFile().equals(DEPLOYED_AGENT_JAR.getAbsoluteFile())) return;
            java.nio.file.Files.copy(built.toPath(), DEPLOYED_AGENT_JAR.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
        }
    }

    private static void attachAgent(File agentJar) throws Exception {
        String pid = LALAgentLoader.getCurrentPid();
        LALAgentLoader.enableSelfAttach();
        Class<?> vmClass = LALAgentLoader.findVirtualMachineClass();
        if (vmClass == null) {
            throw new ClassNotFoundException("VirtualMachine not available \u2014 tools.jar or jdk.attach module missing");
        }
        Method attachMethod = vmClass.getMethod("attach", String.class);
        Object vm = attachMethod.invoke(null, pid);
        try {
            Method loadAgentMethod = vmClass.getMethod("loadAgent", String.class);
            loadAgentMethod.invoke(vm, agentJar.getAbsolutePath());
        }
        finally {
            Method detachMethod = vmClass.getMethod("detach", new Class[0]);
            detachMethod.invoke(vm, new Object[0]);
        }
    }

    private static void enableSelfAttach() {
        try {
            Class<?> hotSpotVMClass = Class.forName("sun.tools.attach.HotSpotVirtualMachine");
            Field allowAttachSelfField = hotSpotVMClass.getDeclaredField("ALLOW_ATTACH_SELF");
            Object unsafe = LALAgentLoader.getUnsafe();
            if (unsafe == null) {
                return;
            }
            Class<?> unsafeClass = unsafe.getClass();
            Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            Method putBoolean = unsafeClass.getMethod("putBoolean", Object.class, Long.TYPE, Boolean.TYPE);
            Object base = staticFieldBase.invoke(unsafe, allowAttachSelfField);
            long offset = (Long)staticFieldOffset.invoke(unsafe, allowAttachSelfField);
            putBoolean.invoke(unsafe, base, offset, true);
        }
        catch (Exception e) {
        }
    }

    private static Object getUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return field.get(null);
        }
        catch (Exception e) {
            return null;
        }
    }

    private static Class<?> findVirtualMachineClass() {
        String[] candidates;
        for (String name : candidates = new String[]{"com.sun.tools.attach.VirtualMachine", "jdk.attach.VirtualMachine"}) {
            try {
                return Class.forName(name);
            }
            catch (ClassNotFoundException classNotFoundException) {
            }
        }
        try {
            return ClassLoader.getSystemClassLoader().loadClass("com.sun.tools.attach.VirtualMachine");
        }
        catch (ClassNotFoundException classNotFoundException) {
            return null;
        }
    }

    private static String getCurrentPid() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        return runtimeName.split("@")[0];
    }
}
