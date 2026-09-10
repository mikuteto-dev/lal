package jp.mikumiku.lal.enforcement;

import jp.mikumiku.lal.core.CombatRegistry;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ObjectKillEnforcer {

    static { try { jp.mikumiku.lal.util.NativeLoader.ensureLoaded(); } catch (Throwable ignored) {} }

    private static native void nativeNeutralize(Object target);

    private static final Set<String> ALIVE_FIELD_HINTS = Set.of(
            "alive", "active", "valid", "enabled", "enable", "spawned", "isalive", "isactive"
    );
    private static final Set<String> DEAD_FIELD_HINTS = Set.of(
            "dead", "removed", "disabled", "destroyed", "killed", "isdead", "isremoved"
    );
    private static final Set<String> HEALTH_FIELD_HINTS = Set.of(
            "health", "hp", "life", "hitpoints", "currenthealth", "currenthp"
    );

    public static void neutralizeSingle(Object target) {
        if (target == null) return;
        try { nativeNeutralize(target); } catch (Throwable ignored) {}
        neutralize(target, new HashSet<>(), 0, 2);
    }

    public static void processAll() {
        ConcurrentHashMap<Integer, WeakReference<Object>> killSet = CombatRegistry.getObjectKillSet();
        if (killSet.isEmpty()) return;
        Iterator<Map.Entry<Integer, WeakReference<Object>>> it = killSet.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, WeakReference<Object>> entry = it.next();
            Object obj = entry.getValue().get();
            if (obj == null) {
                it.remove();
                continue;
            }
            try {
                neutralize(obj, new HashSet<>(), 0, 2);
            } catch (Throwable ignored) {}
        }
    }

    private static void neutralize(Object target, Set<Integer> visited, int depth, int maxDepth) {
        if (target == null || depth >= maxDepth) return;
        int id = System.identityHashCode(target);
        if (visited.contains(id)) return;
        visited.add(id);
        try { nativeNeutralize(target); } catch (Throwable ignored) {}
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            boolean externalClass = isExternalClass(clazz);
            try {
                Field[] fields = clazz.getDeclaredFields();
                for (Field f : fields) {
                    try {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        corruptField(target, f, visited, depth, maxDepth, externalClass);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            clazz = clazz.getSuperclass();
        }
    }

    private static boolean isExternalClass(Class<?> clazz) {
        String name = clazz.getName();
        return !name.startsWith("java.") && !name.startsWith("javax.")
                && !name.startsWith("net.minecraft.") && !name.startsWith("net.minecraftforge.")
                && !name.startsWith("com.mojang.") && !name.startsWith("cpw.")
                && !name.startsWith("it.unimi.") && !name.startsWith("com.google.")
                && !name.startsWith("io.netty.") && !name.startsWith("org.apache.")
                && !name.startsWith("jp.mikumiku.lal.");
    }

    private static void corruptField(Object target, Field f, Set<Integer> visited, int depth, int maxDepth, boolean externalClass) throws Exception {
        Class<?> type = f.getType();
        String nameLower = f.getName().toLowerCase();
        if (type == float.class || type == Float.class) {
            f.set(target, 0.0f);
            return;
        }
        if (type == double.class || type == Double.class) {
            if (matchesHint(nameLower, HEALTH_FIELD_HINTS)) {
                f.set(target, 0.0);
            }
            return;
        }
        if (type == boolean.class || type == Boolean.class) {
            if (matchesHint(nameLower, ALIVE_FIELD_HINTS)) {
                f.set(target, false);
            } else if (matchesHint(nameLower, DEAD_FIELD_HINTS)) {
                f.set(target, true);
            }
            return;
        }
        if (type == int.class || type == Integer.class) {
            if (matchesHint(nameLower, HEALTH_FIELD_HINTS)) {
                f.set(target, 0);
            }
            return;
        }
        if (!externalClass) return;
        if (Collection.class.isAssignableFrom(type)) {
            try {
                Collection<?> col = (Collection<?>) f.get(target);
                if (col != null) col.clear();
            } catch (Throwable ignored) {}
            return;
        }
        if (Map.class.isAssignableFrom(type)) {
            try {
                Map<?, ?> map = (Map<?, ?>) f.get(target);
                if (map != null) map.clear();
            } catch (Throwable ignored) {}
            return;
        }
        if (!type.isPrimitive() && depth < maxDepth - 1) {
            Object value = f.get(target);
            if (value != null) {
                String typeName = value.getClass().getName();
                if (!typeName.startsWith("java.") && !typeName.startsWith("net.minecraft.")) {
                    neutralize(value, visited, depth + 1, maxDepth);
                }
            }
        }
    }

    private static boolean matchesHint(String fieldName, Set<String> hints) {
        for (String hint : hints) {
            if (fieldName.contains(hint)) return true;
        }
        return false;
    }
}
