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
        try {
            File agentJar = LALAgentLoader.createAgentJar();
            LALAgentLoader.attachAgent(agentJar);
        }
        catch (Exception e) {
        }
    }

    private static File createAgentJar() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "lal-agent");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File agentJar = new File(tempDir, "lal-agent.jar");
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Agent-Class", "jp.mikumiku.lal.agent.LALAgent");
        attrs.putValue("Can-Retransform-Classes", "true");
        attrs.putValue("Can-Redefine-Classes", "true");
        JarOutputStream jos = new JarOutputStream((OutputStream)new FileOutputStream(agentJar), manifest);
        jos.close();
        return agentJar;
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
