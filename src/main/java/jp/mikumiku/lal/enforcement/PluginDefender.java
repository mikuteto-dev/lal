package jp.mikumiku.lal.enforcement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class PluginDefender {

    private static volatile boolean initialized = false;
    private static volatile Object launcherInstance;
    private static volatile Object launchPluginHandler;
    private static volatile Field pluginsMapField;
    private static final AtomicReference<Map<String, Object>> pluginBackup = new AtomicReference<>();
    private static final AtomicReference<Object> mapReferenceBackup = new AtomicReference<>();
    private static volatile String lalPluginKey = "zzz_lal_plugin";
    private static volatile Object lalPluginInstance;

    private static Object unsafeInstance;
    private static Method unsafeGetObject;
    private static Method unsafePutObject;
    private static Method unsafeObjectFieldOffset;
    private static long pluginsMapFieldOffset = -1;

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            initUnsafe();
        } catch (Throwable ignored) {}
        try {
            acquireLauncherReferences();
        } catch (Throwable ignored) {}
        if (launchPluginHandler != null && pluginsMapField != null) {
            try {
                backupPlugins();
            } catch (Throwable ignored) {}
            try {
                installProtectedMap();
            } catch (Throwable ignored) {}
            startFastDaemon();
            startMediumDaemon();
            startSlowDaemon();
        }
    }

    private static void initUnsafe() {
        try {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafeInstance = theUnsafe.get(null);
            Class<?> uc = unsafeInstance.getClass();
            unsafeGetObject = uc.getMethod("getObject", Object.class, long.class);
            unsafePutObject = uc.getMethod("putObject", Object.class, long.class, Object.class);
            unsafeObjectFieldOffset = uc.getMethod("objectFieldOffset", Field.class);
        } catch (Throwable ignored) {}
    }

    private static void acquireLauncherReferences() {
        try {
            Class<?> launcherClass = Class.forName("cpw.mods.modlauncher.Launcher");
            Field instanceField = launcherClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            launcherInstance = instanceField.get(null);
            if (launcherInstance == null) return;
            Field lpField = null;
            for (Field f : launcherClass.getDeclaredFields()) {
                if (f.getType().getSimpleName().equals("LaunchPluginHandler")) {
                    f.setAccessible(true);
                    lpField = f;
                    break;
                }
            }
            if (lpField == null) {
                for (String name : new String[]{"launchPlugins", "pluginHandler"}) {
                    try {
                        lpField = launcherClass.getDeclaredField(name);
                        lpField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (lpField == null) return;
            launchPluginHandler = lpField.get(launcherInstance);
            if (launchPluginHandler == null) return;
            Class<?> handlerClass = launchPluginHandler.getClass();
            for (Field f : handlerClass.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType()) && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    pluginsMapField = f;
                    break;
                }
            }
            if (pluginsMapField != null && unsafeInstance != null && unsafeObjectFieldOffset != null) {
                pluginsMapFieldOffset = (long) unsafeObjectFieldOffset.invoke(unsafeInstance, pluginsMapField);
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void backupPlugins() {
        try {
            Map<String, Object> currentMap = (Map<String, Object>) pluginsMapField.get(launchPluginHandler);
            if (currentMap == null) return;
            Object lalPlugin = currentMap.get(lalPluginKey);
            if (lalPlugin != null) {
                lalPluginInstance = lalPlugin;
            }
            Map<String, Object> backup = new HashMap<>(currentMap);
            pluginBackup.set(backup);
            mapReferenceBackup.set(currentMap);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void installProtectedMap() {
        try {
            Map<String, Object> currentMap = (Map<String, Object>) pluginsMapField.get(launchPluginHandler);
            if (currentMap == null) return;
            if (currentMap instanceof LALPluginsMap) return;
            LALPluginsMap protectedMap = new LALPluginsMap(lalPluginKey);
            protectedMap.putAll(currentMap);
            pluginsMapField.set(launchPluginHandler, protectedMap);
            mapReferenceBackup.set(protectedMap);
            backupPlugins();
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void fastCheck() {
        if (launchPluginHandler == null || pluginsMapField == null) return;
        try {
            Map<String, Object> currentMap = null;
            if (unsafeInstance != null && pluginsMapFieldOffset >= 0) {
                currentMap = (Map<String, Object>) unsafeGetObject.invoke(unsafeInstance, launchPluginHandler, pluginsMapFieldOffset);
            } else {
                currentMap = (Map<String, Object>) pluginsMapField.get(launchPluginHandler);
            }
            if (currentMap == null) return;
            if (!currentMap.containsKey(lalPluginKey)) {
                if (lalPluginInstance != null) {
                    currentMap.put(lalPluginKey, lalPluginInstance);
                } else {
                    Map<String, Object> backup = pluginBackup.get();
                    if (backup != null && backup.containsKey(lalPluginKey)) {
                        currentMap.put(lalPluginKey, backup.get(lalPluginKey));
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void mediumCheck() {
        if (launchPluginHandler == null || pluginsMapField == null) return;
        try {
            Map<String, Object> currentMap = (Map<String, Object>) pluginsMapField.get(launchPluginHandler);
            if (currentMap == null) return;
            if (!(currentMap instanceof LALPluginsMap)) {
                installProtectedMap();
            }
            fastCheck();
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void slowCheck() {
        if (launchPluginHandler == null || pluginsMapField == null) return;
        try {
            Object expectedRef = mapReferenceBackup.get();
            Object actualRef = null;
            if (unsafeInstance != null && pluginsMapFieldOffset >= 0) {
                actualRef = unsafeGetObject.invoke(unsafeInstance, launchPluginHandler, pluginsMapFieldOffset);
            } else {
                actualRef = pluginsMapField.get(launchPluginHandler);
            }
            if (expectedRef != null && actualRef != expectedRef) {
                if (unsafeInstance != null && pluginsMapFieldOffset >= 0) {
                    unsafePutObject.invoke(unsafeInstance, launchPluginHandler, pluginsMapFieldOffset, expectedRef);
                } else {
                    pluginsMapField.set(launchPluginHandler, expectedRef);
                }
            }
            mediumCheck();
            backupPlugins();
        } catch (Throwable ignored) {}
    }

    private static void startFastDaemon() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    try { Thread.sleep(5); } catch (InterruptedException e) { Thread.interrupted(); continue; }
                    try { fastCheck(); } catch (Throwable ignored) {}
                } catch (ThreadDeath td) { continue; }
            }
        }, "Thread-" + UUID.randomUUID().toString().substring(0, 8));
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private static void startMediumDaemon() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.interrupted(); continue; }
                    try { mediumCheck(); } catch (Throwable ignored) {}
                } catch (ThreadDeath td) { continue; }
            }
        }, "Thread-" + UUID.randomUUID().toString().substring(0, 8));
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private static void startSlowDaemon() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.interrupted(); continue; }
                    try { slowCheck(); } catch (Throwable ignored) {}
                } catch (ThreadDeath td) { continue; }
            }
        }, "Thread-" + UUID.randomUUID().toString().substring(0, 8));
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY - 1);
        t.start();
    }

    static class LALPluginsMap extends HashMap<String, Object> {
        private final String protectedKey;

        LALPluginsMap(String protectedKey) {
            super();
            this.protectedKey = protectedKey;
        }

        @Override
        public Object remove(Object key) {
            if (protectedKey.equals(key)) {
                return get(key);
            }
            return super.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            if (protectedKey.equals(key)) {
                return false;
            }
            return super.remove(key, value);
        }

        @Override
        public void clear() {
            Object protected_ = get(protectedKey);
            super.clear();
            if (protected_ != null) {
                put(protectedKey, protected_);
            }
        }

        @Override
        public Object replace(String key, Object value) {
            if (protectedKey.equals(key)) {
                return get(key);
            }
            return super.replace(key, value);
        }

        @Override
        public boolean replace(String key, Object oldValue, Object newValue) {
            if (protectedKey.equals(key)) {
                return false;
            }
            return super.replace(key, oldValue, newValue);
        }
    }
}
