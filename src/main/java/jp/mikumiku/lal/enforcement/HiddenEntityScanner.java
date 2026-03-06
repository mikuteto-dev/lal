package jp.mikumiku.lal.enforcement;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class HiddenEntityScanner {

    private static volatile int lastScanTick = 0;
    private static final int SCAN_INTERVAL = 200;

    public static List<LivingEntity> findHiddenLivingEntities(ServerLevel level) {
        List<LivingEntity> hidden = new ArrayList<>();
        try {
            ConcurrentHashMap<UUID, WeakReference<Entity>> constructed =
                    EntityMethodHooks.getConstructedEntities();
            for (Map.Entry<UUID, WeakReference<Entity>> entry : constructed.entrySet()) {
                try {
                    WeakReference<Entity> ref = entry.getValue();
                    if (ref == null) continue;
                    Entity entity = ref.get();
                    if (entity == null) continue;
                    if (!(entity instanceof LivingEntity living)) continue;
                    if (entity instanceof Player) continue;
                    String className = entity.getClass().getName();
                    if (className.startsWith("jp.mikumiku.lal")) continue;
                    UUID uuid = entry.getKey();
                    Entity inLevel = level.getEntity(uuid);
                    if (inLevel != null) continue;
                    hidden.add(living);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return hidden;
    }

    public static void killAllHidden(ServerLevel level, @Nullable Entity attacker) {
        try {
            List<LivingEntity> hidden = findHiddenLivingEntities(level);
            for (LivingEntity living : hidden) {
                try {
                    UUID uuid = living.getUUID();
                    if (CombatRegistry.isInImmortalSet(uuid)) continue;
                    if (CombatRegistry.isDeadConfirmed(uuid)) continue;
                    CombatRegistry.trackDirectEntityRef(uuid, living);
                    EntityMethodHooks.setBypass(true);
                    try {
                        KillEnforcer.forceKill(living, level, attacker);
                    } finally {
                        EntityMethodHooks.setBypass(false);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static List<LivingEntity> findEntitiesFromModCollections(Set<Class<?>> modClasses) {
        List<LivingEntity> found = new ArrayList<>();
        try {
            for (Class<?> clazz : modClasses) {
                try {
                    findLivingEntitiesInClass(clazz, found);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return found;
    }

    private static void findLivingEntitiesInClass(Class<?> clazz, List<LivingEntity> out) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("java.") || c.getName().startsWith("net.minecraft.")) break;
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val == null) continue;
                    if (val instanceof List<?> list) {
                        extractFromList(list, out);
                    } else if (val instanceof Set<?> set) {
                        extractFromCollection(set, out);
                    } else if (val instanceof Map<?, ?> map) {
                        extractFromCollection(map.values(), out);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void extractFromList(List<?> list, List<LivingEntity> out) {
        try {
            int size = list.size();
            for (int i = 0; i < size; i++) {
                try {
                    Object element = list.get(i);
                    if (element instanceof LivingEntity living) {
                        if (living instanceof Player) continue;
                        out.add(living);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void extractFromCollection(Collection<?> col, List<LivingEntity> out) {
        try {
            for (Object element : new ArrayList<>(col)) {
                try {
                    if (element instanceof LivingEntity living) {
                        if (living instanceof Player) continue;
                        out.add(living);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void periodicScan(ServerLevel level, int currentTick) {
        try {
            if (currentTick - lastScanTick < SCAN_INTERVAL) return;
            lastScanTick = currentTick;
            List<LivingEntity> hidden = findHiddenLivingEntities(level);
            for (LivingEntity living : hidden) {
                try {
                    UUID uuid = living.getUUID();
                    CombatRegistry.trackDirectEntityRef(uuid, living);
                    if (CombatRegistry.isInKillSet(uuid)) {
                        KillEnforcer.enforceDeathState(living);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}
