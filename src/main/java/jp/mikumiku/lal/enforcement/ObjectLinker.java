package jp.mikumiku.lal.enforcement;

import jp.mikumiku.lal.agent.LALAgentBridge;
import jp.mikumiku.lal.core.CombatRegistry;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ObjectLinker {

    static { try { jp.mikumiku.lal.util.NativeLoader.ensureLoaded(); } catch (Throwable ignored) {} }

    private static native Object[] nativeGetFieldValues(Object target);
    private static native Object[] nativeGetStaticFieldValues(Class<?> targetClass);
    private static native Object[] nativeExtractElements(Object container);
    private static native boolean nativeRemoveFromCollection(Object collection, Object element);

    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "java.", "javax.", "sun.", "jdk.", "com.sun.",
            "net.minecraft.", "net.minecraftforge.", "com.mojang.",
            "jp.mikumiku.lal.",
            "org.spongepowered.", "cpw.mods.", "org.objectweb.",
            "it.unimi.dsi."
    );

    private static volatile Object unsafeInstance;
    private static volatile boolean unsafeResolved = false;

    public static void scanAndRegister(Object proxyEntity) {
        if (proxyEntity == null) return;
        String proxyClassName = proxyEntity.getClass().getName();
        String proxyPackage = extractPackagePrefix(proxyClassName);
        if (proxyPackage == null || !isExternalPackage(proxyClassName)) return;

        Set<Integer> visited = new HashSet<>();
        visited.add(System.identityHashCode(proxyEntity));
        List<Object> discovered = new ArrayList<>();

        scanInstanceFields(proxyEntity, discovered, visited, 0, 3);

        scanClassStaticFields(proxyEntity.getClass(), discovered, visited, proxyPackage);

        try {
            List<CombatRegistry.TickSource> externalCalls = TickSourceLocator.getExternalCalls();
            Set<String> scannedClasses = new HashSet<>();
            for (CombatRegistry.TickSource source : externalCalls) {
                String ownerDotName = source.ownerClass.replace('/', '.');
                if (ownerDotName.startsWith(proxyPackage) && !scannedClasses.contains(ownerDotName)) {
                    scannedClasses.add(ownerDotName);
                    try {
                        Class<?> mgmtClass = Class.forName(ownerDotName);
                        scanClassStaticFields(mgmtClass, discovered, visited, proxyPackage);
                        CombatRegistry.registerTickSource(proxyEntity, source);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            scanLoadedClassesInPackage(proxyPackage, discovered, visited);
        } catch (Throwable ignored) {}

        for (Object obj : discovered) {
            if (obj instanceof LivingEntity) {
                LivingEntity le = (LivingEntity) obj;
                try {
                    CombatRegistry.addToKillSet(le.getUUID());
                    EnforcementDaemon.trackEntity(le);
                } catch (Throwable ignored) {}
            }
            try {
                TickSourceLocator.locate(obj);
            } catch (Throwable ignored) {}
        }

        if (!discovered.isEmpty()) {
            Set<Class<?>> allModClasses = collectAllModClasses(proxyPackage, proxyEntity.getClass());

            try {
                killProxyEntities(discovered);
            } catch (Throwable ignored) {}

            try {
                markAsRemoved(discovered);
            } catch (Throwable ignored) {}

            for (Object target : discovered) {
                try {
                    removeFromStaticCollections(target, allModClasses);
                } catch (Throwable ignored) {}
            }

            for (Object target : discovered) {
                try {
                    forceZeroListsContaining(target, allModClasses);
                } catch (Throwable ignored) {}
            }

            try {
                corruptPackageSecurityFields(allModClasses);
            } catch (Throwable ignored) {}

            try {
                DynamicTickRemover.applyImmediate();
            } catch (Throwable ignored) {}
            try {
                purgeKilledObjectsFromCollections();
            } catch (Throwable ignored) {}

            try {
                killProxyEntities(discovered);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * One snapshot: getAllLoadedClasses materialises a fresh array of every loaded class, and
     * scanAndRegister asked three times.
     */
    private static final long LOADED_CLASSES_TTL_MS = 10_000L;
    private static volatile Class<?>[] loadedClassesCache;
    private static volatile long loadedClassesAtMs = 0L;

    private static Class<?>[] loadedClasses() {
        Class<?>[] cached = loadedClassesCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - loadedClassesAtMs < LOADED_CLASSES_TTL_MS) {
            return cached;
        }
        try {
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst != null) {
                Class<?>[] all = inst.getAllLoadedClasses();
                loadedClassesCache = all;
                loadedClassesAtMs = now;
                return all;
            }
        } catch (Throwable ignored) {}
        return cached != null ? cached : new Class<?>[0];
    }

    private static Set<Class<?>> collectAllModClasses(String packagePrefix, Class<?> entityClass) {
        Set<Class<?>> classes = new HashSet<>();
        classes.add(entityClass);
        try {
            for (Class<?> clazz : loadedClasses()) {
                if (clazz.getName().startsWith(packagePrefix)) {
                    classes.add(clazz);
                }
            }
        } catch (Throwable ignored) {}
        return classes;
    }

    private static void scanClassStaticFields(Class<?> clazz, List<Object> results, Set<Integer> visited, String targetPackage) {
        boolean nativeOk = false;
        try {
            Object[] nativeVals = nativeGetStaticFieldValues(clazz);
            if (nativeVals != null) {
                nativeOk = true;
                for (Object value : nativeVals) {
                    if (value == null) continue;
                    extractFromContainer(value, results, visited, targetPackage, 0, 3);
                }
            }
        } catch (Throwable ignored) {}
        if (!nativeOk) {
            Class<?> current = clazz;
            while (current != null && current != Object.class) {
                try {
                    for (Field f : current.getDeclaredFields()) {
                        try {
                            if (!Modifier.isStatic(f.getModifiers())) continue;
                            if (f.getType().isPrimitive()) continue;
                            f.setAccessible(true);
                            Object value = f.get(null);
                            if (value == null) continue;
                            extractFromContainer(value, results, visited, targetPackage, 0, 3);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                current = current.getSuperclass();
            }
        }
        try {
            Class<?> enclosing = clazz.getEnclosingClass();
            if (enclosing != null && isExternalPackage(enclosing.getName())) {
                scanClassStaticFields(enclosing, results, visited, targetPackage);
            }
        } catch (Throwable ignored) {}
    }

    private static void extractFromContainer(Object container, List<Object> results, Set<Integer> visited, String targetPackage, int depth, int maxDepth) {
        if (container == null || depth >= maxDepth) return;
        int id = System.identityHashCode(container);
        if (visited.contains(id)) return;
        visited.add(id);

        String typeName = container.getClass().getName();
        if (isExternalPackage(typeName) && typeName.startsWith(targetPackage)) {
            if (!(container instanceof Collection) && !(container instanceof Map)) {
                results.add(container);
            }
        }

        if (container instanceof Collection) {
            try {
                Object[] snapshot = null;
                try { snapshot = nativeExtractElements(container); } catch (Throwable ignored) {}
                if (snapshot == null) {
                    try { snapshot = ((Collection<?>) container).toArray(); } catch (Throwable ignored) {}
                }
                if (snapshot == null) {
                    snapshot = extractViaIteration((Collection<?>) container);
                }
                for (Object elem : snapshot) {
                    if (elem == null) continue;
                    String elemType = elem.getClass().getName();
                    if (isExternalPackage(elemType)) {
                        int eid = System.identityHashCode(elem);
                        if (!visited.contains(eid)) {
                            visited.add(eid);
                            results.add(elem);
                            if (depth < maxDepth - 1) {
                                scanInstanceFields(elem, results, visited, depth + 1, maxDepth);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return;
        }

        if (container instanceof Map) {
            try {
                for (Object val : ((Map<?, ?>) container).values()) {
                    if (val == null) continue;
                    extractFromContainer(val, results, visited, targetPackage, depth + 1, maxDepth);
                }
            } catch (Throwable ignored) {}
            return;
        }

        if (container.getClass().isArray() && !container.getClass().getComponentType().isPrimitive()) {
            try {
                int len = Array.getLength(container);
                for (int i = 0; i < len; i++) {
                    Object elem = Array.get(container, i);
                    if (elem == null) continue;
                    String elemType = elem.getClass().getName();
                    if (isExternalPackage(elemType)) {
                        int eid = System.identityHashCode(elem);
                        if (!visited.contains(eid)) {
                            visited.add(eid);
                            results.add(elem);
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return;
        }

        if (isExternalPackage(typeName) && depth < maxDepth - 1) {
            try {
                for (Field f : container.getClass().getDeclaredFields()) {
                    try {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType().isPrimitive()) continue;
                        f.setAccessible(true);
                        Object val = f.get(container);
                        if (val == null) continue;
                        extractFromContainer(val, results, visited, targetPackage, depth + 1, maxDepth);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    }

    private static Object[] extractViaIteration(Collection<?> collection) {
        List<Object> items = new ArrayList<>();
        try {
            for (Object item : collection) {
                items.add(item);
            }
        } catch (Throwable ignored) {}
        return items.toArray();
    }

    private static void scanLoadedClassesInPackage(String targetPackage, List<Object> results, Set<Integer> visited) {
        try {
            Class<?>[] loaded = loadedClasses();
            for (Class<?> clazz : loaded) {
                try {
                    String name = clazz.getName();
                    if (!name.startsWith(targetPackage)) continue;
                    for (Field f : clazz.getDeclaredFields()) {
                        try {
                            if (!Modifier.isStatic(f.getModifiers())) continue;
                            if (f.getType().isPrimitive()) continue;
                            f.setAccessible(true);
                            Object value = f.get(null);
                            if (value == null) continue;
                            extractFromContainer(value, results, visited, targetPackage, 0, 3);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void scanInstanceFields(Object target, List<Object> results, Set<Integer> visited, int depth, int maxDepth) {
        if (depth >= maxDepth || target == null) return;
        try {
            Object[] nativeVals = nativeGetFieldValues(target);
            if (nativeVals != null) {
                for (Object value : nativeVals) {
                    if (value == null) continue;
                    int id = System.identityHashCode(value);
                    if (visited.contains(id)) continue;
                    visited.add(id);
                    String typeName = value.getClass().getName();
                    if (isExternalPackage(typeName)) {
                        results.add(value);
                        scanInstanceFields(value, results, visited, depth + 1, maxDepth);
                    } else if (depth < maxDepth - 1) {
                        scanInstanceFields(value, results, visited, depth + 1, maxDepth);
                    }
                }
                return;
            }
        } catch (Throwable ignored) {}
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            if (!isExternalPackage(clazz.getName())) {
                clazz = clazz.getSuperclass();
                continue;
            }
            try {
                Field[] fields = clazz.getDeclaredFields();
                for (Field f : fields) {
                    try {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType().isPrimitive()) continue;
                        if (f.getType().isArray() && f.getType().getComponentType().isPrimitive()) continue;
                        f.setAccessible(true);
                        Object value = f.get(target);
                        if (value == null) continue;
                        int id = System.identityHashCode(value);
                        if (visited.contains(id)) continue;
                        visited.add(id);
                        String typeName = value.getClass().getName();
                        if (isExternalPackage(typeName)) {
                            results.add(value);
                            scanInstanceFields(value, results, visited, depth + 1, maxDepth);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            clazz = clazz.getSuperclass();
        }
    }

    private static String extractPackagePrefix(String className) {
        String[] parts = className.split("\\.");
        if (parts.length >= 3) {
            return parts[0] + "." + parts[1] + ".";
        }
        if (parts.length == 2) {
            return parts[0] + ".";
        }
        return null;
    }

    public static void purgeKilledObjectsFromCollections() {
        Map<Integer, WeakReference<Object>> killSet = CombatRegistry.getObjectKillSet();
        if (killSet.isEmpty()) return;
        HashMap<String, List<Object>> byPackage = new HashMap<>();
        for (WeakReference<Object> ref : killSet.values()) {
            Object obj = ref.get();
            if (obj == null) continue;
            String pkg = extractPackagePrefix(obj.getClass().getName());
            if (pkg != null && isExternalPackage(obj.getClass().getName())) {
                byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(obj);
            }
        }
        for (Map.Entry<String, List<Object>> entry : byPackage.entrySet()) {
            purgeFromPackageCollections(entry.getKey(), entry.getValue());
        }
    }

    private static void purgeFromPackageCollections(String targetPackage, List<Object> targets) {
        Set<Integer> targetIds = new HashSet<>();
        for (Object obj : targets) targetIds.add(System.identityHashCode(obj));
        try {
            Class<?>[] loaded = loadedClasses();
            for (Class<?> clazz : loaded) {
                if (!clazz.getName().startsWith(targetPackage)) continue;
                try {
                    Object[] staticVals = nativeGetStaticFieldValues(clazz);
                    if (staticVals == null) continue;
                    for (Object val : staticVals) {
                        if (val == null) continue;
                        tryRemoveTargetsFromCollection(val, targets, targetIds);
                        try {
                            Object[] innerVals = nativeGetFieldValues(val);
                            if (innerVals != null) {
                                for (Object inner : innerVals) {
                                    if (inner == null) continue;
                                    tryRemoveTargetsFromCollection(inner, targets, targetIds);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void killProxyEntities(List<Object> discovered) {
        for (Object obj : discovered) {
            try {
                Object[] vals = nativeGetFieldValues(obj);
                if (vals == null) {
                    vals = getFieldValuesReflection(obj);
                }
                if (vals == null) continue;
                for (Object val : vals) {
                    if (val == null) continue;
                    if (val instanceof LivingEntity) {
                        LivingEntity entity = (LivingEntity) val;
                        try {
                            CombatRegistry.addToKillSet(entity.getUUID());
                            EnforcementDaemon.trackEntity(entity);
                        } catch (Throwable ignored) {}
                        try {
                            Level lvl = entity.level();
                            if (lvl instanceof ServerLevel) {
                                LALEntityRemover.deleteFromLevel(entity, (ServerLevel) lvl);
                            }
                        } catch (Throwable ignored) {}
                        try {
                            disableBossEvents(entity);
                        } catch (Throwable ignored) {}
                    }
                    if (val instanceof ServerBossEvent) {
                        try {
                            ((ServerBossEvent) val).setVisible(false);
                            ((ServerBossEvent) val).removeAllPlayers();
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            try {
                disableBossEvents(obj);
            } catch (Throwable ignored) {}
        }
    }

    private static Object[] getFieldValuesReflection(Object target) {
        List<Object> values = new ArrayList<>();
        for (Class<?> clazz = target.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            if (!isExternalPackage(clazz.getName())) continue;
            for (Field f : safeGetDeclaredFields(clazz)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (val != null) values.add(val);
                } catch (Throwable ignored) {}
            }
        }
        return values.isEmpty() ? null : values.toArray();
    }

    private static void disableBossEvents(Object target) {
        for (Class<?> clazz = target.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            if (!isExternalPackage(clazz.getName())) continue;
            for (Field f : safeGetDeclaredFields(clazz)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (val instanceof ServerBossEvent) {
                        ServerBossEvent sbe = (ServerBossEvent) val;
                        sbe.setVisible(false);
                        sbe.removeAllPlayers();
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void markAsRemoved(List<Object> targets) {
        for (Object obj : targets) {
            try {
                for (Class<?> clazz = obj.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                    for (Field f : clazz.getDeclaredFields()) {
                        String name;
                        if (Modifier.isStatic(f.getModifiers()) || f.getType() != boolean.class) continue;
                        name = f.getName().toLowerCase();
                        if (!name.contains("removed") && !name.contains("dead") && !name.contains("killed")
                                && !name.contains("destroy") && !name.contains("invalid") && !name.contains("disposed")) continue;
                        try {
                            f.setAccessible(true);
                            f.setBoolean(obj, true);
                        } catch (Throwable e) {
                            try {
                                Object unsafe = getUnsafe();
                                if (unsafe == null) continue;
                                Class<?> uc = unsafe.getClass();
                                long offset = (long) uc.getMethod("objectFieldOffset", Field.class).invoke(unsafe, f);
                                uc.getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(unsafe, obj, offset, true);
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void removeFromStaticCollections(Object target, Set<Class<?>> searchClasses) {
        for (Class<?> searchClass : searchClasses) {
            for (Class<?> clazz = searchClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : safeGetDeclaredFields(clazz)) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val == null) continue;

                        if (val instanceof List) {
                            List<?> list = (List<?>) val;
                            try { list.remove(target); } catch (Throwable ignored) {}
                            forceRemoveFromList(list, target);
                            if (isExternalPackage(val.getClass().getName()) || listContainsViaUnsafe(list, target)) {
                                forceZeroArrayList(list);
                                forceReplaceStaticField(f, new ArrayList<>());
                            }
                            continue;
                        }

                        if (val instanceof Set) {
                            try { ((Set<?>) val).remove(target); } catch (Throwable ignored) {}
                            forceRemoveFromSet((Set<?>) val, target);
                            if (isExternalPackage(val.getClass().getName())) {
                                forceReplaceStaticField(f, new HashSet<>());
                            }
                            continue;
                        }

                        if (val instanceof Map) {
                            try { ((Map<?, ?>) val).remove(target); } catch (Throwable ignored) {}
                            forceRemoveFromMap((Map<?, ?>) val, target);
                            forceRemoveValueFromMap((Map<?, ?>) val, target);
                            if (isExternalPackage(val.getClass().getName())) {
                                forceReplaceStaticField(f, new HashMap<>());
                            }
                            continue;
                        }

                        if (!val.getClass().isPrimitive() && !val.getClass().getName().startsWith("java.")
                                && !val.getClass().getName().startsWith("net.minecraft.")) {
                            scanInstanceFieldsForTarget(val, target);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static void scanInstanceFieldsForTarget(Object obj, Object target) {
        try {
            for (Class<?> clazz = obj.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                if (!isExternalPackage(clazz.getName())) continue;
                for (Field f : safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val == null) continue;
                        if (val instanceof List) {
                            List<?> list = (List<?>) val;
                            try { list.remove(target); } catch (Throwable ignored) {}
                            forceRemoveFromList(list, target);
                            if (listContainsViaUnsafe(list, target)) {
                                forceReplaceInstanceField(f, obj, new ArrayList<>());
                            }
                            continue;
                        }
                        if (val instanceof Set) {
                            try { ((Set<?>) val).remove(target); } catch (Throwable ignored) {}
                            forceRemoveFromSet((Set<?>) val, target);
                            continue;
                        }
                        if (val instanceof Map) {
                            try { ((Map<?, ?>) val).remove(target); } catch (Throwable ignored) {}
                            forceRemoveFromMap((Map<?, ?>) val, target);
                            forceRemoveValueFromMap((Map<?, ?>) val, target);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void forceZeroListsContaining(Object target, Set<Class<?>> searchClasses) {
        for (Class<?> searchClass : searchClasses) {
            for (Class<?> clazz = searchClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : safeGetDeclaredFields(clazz)) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val == null || !(val instanceof ArrayList)) continue;
                        if (listContainsViaUnsafe((List<?>) val, target)) {
                            forceZeroArrayList(val);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static void tryRemoveTargetsFromCollection(Object container, List<Object> targets, Set<Integer> targetIds) {
        try {
            Object[] elements = nativeExtractElements(container);
            if (elements == null) return;
            for (Object elem : elements) {
                if (elem == null) continue;
                if (targetIds.contains(System.identityHashCode(elem))) {
                    try { nativeRemoveFromCollection(container, elem); } catch (Throwable ignored) {}
                    forceRemoveFromList(container, elem);
                    if (container instanceof List && listContainsViaUnsafe((List<?>) container, elem)) {
                        forceZeroArrayList(container);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void forceRemoveFromList(Object list, Object target) {
        if (!(list instanceof List)) return;
        if (forceRemoveFromListReflection(list, target)) return;
        forceRemoveFromListUnsafe(list, target);
    }

    private static boolean forceRemoveFromListReflection(Object list, Object target) {
        try {
            Field elementDataField = ArrayList.class.getDeclaredField("elementData");
            elementDataField.setAccessible(true);
            Field sizeField = ArrayList.class.getDeclaredField("size");
            sizeField.setAccessible(true);
            Object[] data = (Object[]) elementDataField.get(list);
            int size = sizeField.getInt(list);
            if (data == null) return false;
            boolean removed = false;
            for (int i = size - 1; i >= 0; i--) {
                if (data[i] != target && (data[i] == null || !data[i].equals(target))) continue;
                System.arraycopy(data, i + 1, data, i, size - i - 1);
                data[size - 1] = null;
                sizeField.setInt(list, --size);
                removed = true;
            }
            if (removed) {
                try {
                    Field modCountField = java.util.AbstractList.class.getDeclaredField("modCount");
                    modCountField.setAccessible(true);
                    modCountField.setInt(list, modCountField.getInt(list) + 1);
                } catch (Throwable ignored) {}
            }
            return removed;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void forceRemoveFromListUnsafe(Object list, Object target) {
        try {
            Object unsafe = getUnsafe();
            if (unsafe == null) return;
            Class<?> uc = unsafe.getClass();
            Method objectFieldOffset = uc.getMethod("objectFieldOffset", Field.class);
            Method getObject = uc.getMethod("getObject", Object.class, long.class);
            Method getInt = uc.getMethod("getInt", Object.class, long.class);
            Method putInt = uc.getMethod("putInt", Object.class, long.class, int.class);
            Field elementDataField = ArrayList.class.getDeclaredField("elementData");
            Field sizeField = ArrayList.class.getDeclaredField("size");
            long dataOffset = (long) objectFieldOffset.invoke(unsafe, elementDataField);
            long sizeOffset = (long) objectFieldOffset.invoke(unsafe, sizeField);
            Object[] data = (Object[]) getObject.invoke(unsafe, list, dataOffset);
            int size = (int) getInt.invoke(unsafe, list, sizeOffset);
            if (data == null || size <= 0) return;
            boolean removed = false;
            for (int i = size - 1; i >= 0; i--) {
                if (data[i] != target && (data[i] == null || !data[i].equals(target))) continue;
                System.arraycopy(data, i + 1, data, i, size - i - 1);
                data[size - 1] = null;
                putInt.invoke(unsafe, list, sizeOffset, --size);
                removed = true;
            }
            if (removed) {
                try {
                    Field modCountField = java.util.AbstractList.class.getDeclaredField("modCount");
                    long mcOffset = (long) objectFieldOffset.invoke(unsafe, modCountField);
                    int mc = (int) getInt.invoke(unsafe, list, mcOffset);
                    putInt.invoke(unsafe, list, mcOffset, mc + 1);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static boolean listContainsViaUnsafe(List<?> list, Object target) {
        try {
            Object unsafe = getUnsafe();
            if (unsafe == null) return false;
            Class<?> uc = unsafe.getClass();
            Method objectFieldOffset = uc.getMethod("objectFieldOffset", Field.class);
            Method getObject = uc.getMethod("getObject", Object.class, long.class);
            Method getInt = uc.getMethod("getInt", Object.class, long.class);
            Field edf = ArrayList.class.getDeclaredField("elementData");
            Field sf = ArrayList.class.getDeclaredField("size");
            long dOff = (long) objectFieldOffset.invoke(unsafe, edf);
            long sOff = (long) objectFieldOffset.invoke(unsafe, sf);
            Object[] data = (Object[]) getObject.invoke(unsafe, list, dOff);
            int size = (int) getInt.invoke(unsafe, list, sOff);
            if (data == null) return false;
            for (int i = 0; i < size; i++) {
                if (data[i] == target || (data[i] != null && data[i].equals(target))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void forceZeroArrayList(Object list) {
        try {
            Object unsafe = getUnsafe();
            if (unsafe == null) return;
            Class<?> uc = unsafe.getClass();
            Method ofo = uc.getMethod("objectFieldOffset", Field.class);
            Method pi = uc.getMethod("putInt", Object.class, long.class, int.class);
            Method po = uc.getMethod("putObject", Object.class, long.class, Object.class);
            Field sf = ArrayList.class.getDeclaredField("size");
            Field edf = ArrayList.class.getDeclaredField("elementData");
            long sOff = (long) ofo.invoke(unsafe, sf);
            long dOff = (long) ofo.invoke(unsafe, edf);
            pi.invoke(unsafe, list, sOff, 0);
            po.invoke(unsafe, list, dOff, new Object[0]);
        } catch (Throwable ignored) {}
    }

    private static void forceReplaceStaticField(Field f, Object newValue) {
        try {
            Object unsafe = getUnsafe();
            if (unsafe == null) return;
            Class<?> uc = unsafe.getClass();
            Object base = uc.getMethod("staticFieldBase", Field.class).invoke(unsafe, f);
            long offset = (long) uc.getMethod("staticFieldOffset", Field.class).invoke(unsafe, f);
            uc.getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, base, offset, newValue);
        } catch (Throwable ignored) {}
    }

    private static void forceReplaceInstanceField(Field f, Object owner, Object newValue) {
        try {
            Object unsafe = getUnsafe();
            if (unsafe == null) return;
            Class<?> uc = unsafe.getClass();
            long offset = (long) uc.getMethod("objectFieldOffset", Field.class).invoke(unsafe, f);
            uc.getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, owner, offset, newValue);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void forceRemoveFromSet(Set<?> set, Object target) {
        try {
            for (Field f : safeGetDeclaredFields(set.getClass())) {
                f.setAccessible(true);
                Object val = f.get(set);
                if (val instanceof Map) {
                    forceRemoveFromMap((Map<?, ?>) val, target);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void forceRemoveFromMap(Map<?, ?> map, Object key) {
        try {
            Field tableField = findAccessibleField(map.getClass(), "table");
            if (tableField == null) tableField = findAccessibleField(HashMap.class, "table");
            if (tableField == null) return;
            Object[] table = (Object[]) tableField.get(map);
            if (table == null) return;
            Field sizeField = findAccessibleField(map.getClass(), "size");
            if (sizeField == null) sizeField = findAccessibleField(HashMap.class, "size");
            for (int i = 0; i < table.length; i++) {
                Object node = table[i];
                Object prev = null;
                while (node != null) {
                    Object nodeKey = getNodeField(node, "key");
                    Object next = getNodeField(node, "next");
                    if (nodeKey == key || (nodeKey != null && nodeKey.equals(key))) {
                        if (prev == null) {
                            table[i] = next;
                        } else {
                            setNodeField(prev, "next", next);
                        }
                        if (sizeField != null) {
                            sizeField.setInt(map, sizeField.getInt(map) - 1);
                        }
                        break;
                    }
                    prev = node;
                    node = next;
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void forceRemoveValueFromMap(Map<?, ?> map, Object value) {
        try {
            Field tableField = findAccessibleField(map.getClass(), "table");
            if (tableField == null) tableField = findAccessibleField(HashMap.class, "table");
            if (tableField == null) return;
            Object[] table = (Object[]) tableField.get(map);
            if (table == null) return;
            Field sizeField = findAccessibleField(map.getClass(), "size");
            if (sizeField == null) sizeField = findAccessibleField(HashMap.class, "size");
            for (int i = 0; i < table.length; i++) {
                Object node = table[i];
                Object prev = null;
                while (node != null) {
                    Object nodeValue = getNodeField(node, "value");
                    Object next = getNodeField(node, "next");
                    if (nodeValue == value || (nodeValue != null && nodeValue.equals(value))) {
                        if (prev == null) {
                            table[i] = next;
                        } else {
                            setNodeField(prev, "next", next);
                        }
                        if (sizeField != null) {
                            sizeField.setInt(map, sizeField.getInt(map) - 1);
                        }
                        prev = null;
                        node = next;
                        continue;
                    }
                    prev = node;
                    node = next;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Object getNodeField(Object node, String fieldName) {
        try {
            for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f.get(node);
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void setNodeField(Object node, String fieldName, Object value) {
        try {
            for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(node, value);
                    return;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static Field findAccessibleField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Object getUnsafe() {
        if (unsafeResolved) return unsafeInstance;
        synchronized (ObjectLinker.class) {
            if (unsafeResolved) return unsafeInstance;
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field f = unsafeClass.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                unsafeInstance = f.get(null);
            } catch (Throwable ignored) {}
            unsafeResolved = true;
        }
        return unsafeInstance;
    }

    private static final Field[] EMPTY_FIELDS = new Field[0];
    // ClassValue, not a Class-keyed map, so reflected-on classes can still be unloaded.
    private static final ClassValue<Field[]> FIELDS_CACHE = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            try {
                return type.getDeclaredFields();
            } catch (Throwable t) {
                return EMPTY_FIELDS;
            }
        }
    };

    private static Field[] safeGetDeclaredFields(Class<?> clazz) {
        try {
            return FIELDS_CACHE.get(clazz);
        } catch (Throwable t) {
            return EMPTY_FIELDS;
        }
    }

    private static void corruptPackageSecurityFields(Set<Class<?>> classes) {
        for (Class<?> clazz : classes) {
            for (Field f : safeGetDeclaredFields(clazz)) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Class<?> type = f.getType();
                    if (type == byte[].class) {
                        try {
                            f.set(null, null);
                        } catch (Throwable e) {
                            try {
                                Object unsafe = getUnsafe();
                                if (unsafe != null) {
                                    Class<?> uc = unsafe.getClass();
                                    Object base = uc.getMethod("staticFieldBase", Field.class).invoke(unsafe, f);
                                    long offset = (long) uc.getMethod("staticFieldOffset", Field.class).invoke(unsafe, f);
                                    uc.getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, base, offset, null);
                                }
                            } catch (Throwable ignored) {}
                        }
                    } else if (ThreadLocal.class.isAssignableFrom(type)) {
                        try {
                            ThreadLocal<?> tl = (ThreadLocal<?>) f.get(null);
                            if (tl != null) tl.remove();
                            f.set(null, new ThreadLocal<>());
                        } catch (Throwable e) {
                            try {
                                Object unsafe = getUnsafe();
                                if (unsafe != null) {
                                    Class<?> uc = unsafe.getClass();
                                    Object base = uc.getMethod("staticFieldBase", Field.class).invoke(unsafe, f);
                                    long offset = (long) uc.getMethod("staticFieldOffset", Field.class).invoke(unsafe, f);
                                    uc.getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, base, offset, new ThreadLocal<>());
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void nullifyWorldReferences(Object target) {
        if (target == null) return;
        for (Class<?> clazz = target.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            if (!isExternalPackage(clazz.getName())) continue;
            for (Field f : safeGetDeclaredFields(clazz)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (val == null) continue;
                    if (val instanceof Level) {
                        try {
                            f.set(target, null);
                        } catch (Throwable e) {
                            try {
                                Object unsafe = getUnsafe();
                                if (unsafe != null) {
                                    Class<?> uc = unsafe.getClass();
                                    long offset = (long) uc.getMethod("objectFieldOffset", Field.class).invoke(unsafe, f);
                                    uc.getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, target, offset, null);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    static boolean isExternalPackage(String className) {
        for (String prefix : ALLOWED_PREFIXES) {
            if (className.startsWith(prefix)) return false;
        }
        return true;
    }
}
