package jp.mikumiku.lal.transformer;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class LALTransformationService implements ITransformationService {

    @Override
    public String name() {
        return "lal_service";
    }

    @Override
    public void initialize(IEnvironment environment) {
        try {
            jp.mikumiku.lal.enforcement.PluginDefender.initialize();
        } catch (Throwable ignored) {}
        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.interrupted();
                    continue;
                }
                try {
                    resetStaticBooleanFlags();
                } catch (Throwable ignored) {}
            }
        }, "Thread-" + UUID.randomUUID().toString().substring(0, 8));
        monitor.setDaemon(true);
        monitor.setPriority(Thread.MIN_PRIORITY + 1);
        monitor.start();
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<ITransformer> transformers() {
        return List.of();
    }

    private static volatile boolean flagsScanDone = false;
    private static final java.util.concurrent.CopyOnWriteArrayList<Field> flagFields =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private static void resetStaticBooleanFlags() {
        if (!flagsScanDone) {
            flagsScanDone = true;
            try {
                scanFlags();
            } catch (Throwable ignored) {}
        }
        for (Field f : flagFields) {
            try {
                if (f.getBoolean(null)) {
                    f.setBoolean(null, false);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void scanFlags() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = LALTransformationService.class.getClassLoader();
            Field classesField = null;
            try {
                classesField = ClassLoader.class.getDeclaredField("classes");
                classesField.setAccessible(true);
            } catch (Throwable ignored) {
                return;
            }
            Object vec = classesField.get(cl);
            if (!(vec instanceof java.util.Vector)) return;
            @SuppressWarnings("unchecked")
            java.util.Vector<Class<?>> classes = (java.util.Vector<Class<?>>) vec;
            Class<?>[] snapshot = classes.toArray(new Class<?>[0]);
            for (Class<?> clazz : snapshot) {
                try {
                    String name = clazz.getName();
                    if (name.startsWith("java.") || name.startsWith("sun.")
                            || name.startsWith("jdk.") || name.startsWith("com.sun.")
                            || name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")
                            || name.startsWith("jp.mikumiku.lal.")) {
                        continue;
                    }
                    for (Field f : clazz.getDeclaredFields()) {
                        try {
                            if (f.getType() == boolean.class
                                    && java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                    && java.lang.reflect.Modifier.isPublic(f.getModifiers())) {
                                f.setAccessible(true);
                                String fn = f.getName().toLowerCase();
                                if (fn.contains("return") || fn.contains("disable")
                                        || fn.contains("bypass") || fn.contains("block")
                                        || fn.contains("cancel") || fn.contains("stop")) {
                                    flagFields.add(f);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

}
