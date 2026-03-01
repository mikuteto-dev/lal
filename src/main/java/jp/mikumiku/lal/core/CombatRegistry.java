package jp.mikumiku.lal.core;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import jp.mikumiku.lal.core.EntityLedger;
import jp.mikumiku.lal.core.EntityLedgerEntry;
import jp.mikumiku.lal.core.LifecycleState;
import net.minecraft.world.entity.Entity;

public class CombatRegistry {
    static {
        try { System.loadLibrary("lal"); } catch (Throwable ignored) {}
    }
    private static native boolean nativeIsInKillSet(long hi, long lo);
    private static native void nativeAddToKillSet(long hi, long lo);
    private static native void nativeRemoveFromKillSet(long hi, long lo);
    private static native boolean nativeIsInImmortalSet(long hi, long lo);
    private static native void nativeAddToImmortalSet(long hi, long lo);
    private static native void nativeRemoveFromImmortalSet(long hi, long lo);
    private static native boolean nativeIsDeadConfirmed(long hi, long lo);
    private static native void nativeConfirmDead(long hi, long lo);
    private static native void nativeClearDeadConfirmed(long hi, long lo);
    private static native void nativeSyncImmortalFromBackup();
    private static final DisableRemoveSet KILL_SET = new DisableRemoveSet();
    private static final DisableRemoveSet IMMORTAL_SET = new DisableRemoveSet();
    private static final DisableRemoveSet IMMORTAL_SET_BACKUP = new DisableRemoveSet();
    private static final DisableRemoveSet DEAD_CONFIRMED = new DisableRemoveSet();
    private static final Map<UUID, Float> FORCED_HEALTH = new ConcurrentHashMap<UUID, Float>();
    private static final Map<UUID, UUID> KILL_ATTACKERS = new ConcurrentHashMap<UUID, UUID>();
    private static final Map<UUID, Integer> KILL_START_TICK = new ConcurrentHashMap<UUID, Integer>();
    public static final int DEATH_ANIMATION_TICKS = 60;
    private static final Set<UUID> LOOT_DROPPED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Integer, WeakReference<Object>> OBJECT_KILL_SET = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, List<TickSource>> OBJECT_TICK_SOURCES = new ConcurrentHashMap<>();

    public static class TickSource {
        public final String ownerClass;
        public final String methodName;
        public final String methodDesc;
        public TickSource(String ownerClass, String methodName, String methodDesc) {
            this.ownerClass = ownerClass;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }
    }

    public CombatRegistry() {
        super();
    }

    public static void addToKillSet(UUID uuid) {
        IMMORTAL_SET.internalRemove(uuid);
        KILL_SET.internalRemove(uuid);
        KILL_SET.add(uuid);
        EntityLedger.get().getOrCreate((UUID)uuid).state = LifecycleState.PENDING_KILL;
        try { nativeAddToKillSet(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
    }

    public static void addToKillSet(UUID uuid, UUID attackerUuid, int tickCount) {
        CombatRegistry.addToKillSet(uuid);
        if (attackerUuid != null) {
            KILL_ATTACKERS.put(uuid, attackerUuid);
        }
        KILL_START_TICK.putIfAbsent(uuid, tickCount);
    }

    public static void removeFromKillSet(UUID uuid) {
        KILL_SET.internalRemove(uuid);
        try { nativeRemoveFromKillSet(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
    }

    public static boolean isInKillSet(Entity entity) {
        if (entity == null) {
            return false;
        }
        return KILL_SET.contains(entity.getUUID());
    }

    public static boolean isInKillSet(UUID uuid) {
        return KILL_SET.contains(uuid);
    }

    public static UUID getKillAttacker(UUID targetUuid) {
        return KILL_ATTACKERS.get(targetUuid);
    }

    public static Integer getKillStartTick(UUID targetUuid) {
        return KILL_START_TICK.get(targetUuid);
    }

    public static boolean isAnimationComplete(UUID targetUuid, int currentTick) {
        Integer startTick = KILL_START_TICK.get(targetUuid);
        if (startTick == null) {
            return true;
        }
        return currentTick - startTick >= 60;
    }

    public static void addToImmortalSet(UUID uuid) {
        KILL_SET.internalRemove(uuid);
        DEAD_CONFIRMED.internalRemove(uuid);
        IMMORTAL_SET.add(uuid);
        IMMORTAL_SET_BACKUP.add(uuid);
        try { nativeAddToImmortalSet(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
    }

    public static void removeFromImmortalSet(UUID uuid) {
        if (!lal$isCallerFromLAL()) return;
        lal$removeFromImmortalSetInternal(uuid);
    }

    public static void lal$removeFromImmortalSetInternal(UUID uuid) {
        IMMORTAL_SET.internalRemove(uuid);
        IMMORTAL_SET_BACKUP.internalRemove(uuid);
        try { nativeRemoveFromImmortalSet(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
    }

    private static boolean lal$isCallerFromLAL() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(stack.length, 15); i++) {
                String className = stack[i].getClassName();
                if (className.startsWith("jp.mikumiku.lal.")) return true;
                String methodName = stack[i].getMethodName();
                if (methodName.startsWith("lal$")) return true;
                if (className.startsWith("java.") || className.startsWith("sun.")
                        || className.startsWith("jdk.") || className.startsWith("com.sun.")) continue;
                return false;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    public static boolean isInImmortalSet(Entity entity) {
        if (entity == null) {
            return false;
        }
        return IMMORTAL_SET.contains(entity.getUUID());
    }

    public static boolean isInImmortalSet(UUID uuid) {
        return IMMORTAL_SET.contains(uuid);
    }

    public static void confirmDead(UUID uuid) {
        KILL_SET.internalRemove(uuid);
        DEAD_CONFIRMED.add(uuid);
        KILL_ATTACKERS.remove(uuid);
        KILL_START_TICK.remove(uuid);
        LOOT_DROPPED.remove(uuid);
        EntityLedgerEntry entry = EntityLedger.get().get(uuid);
        if (entry != null) {
            entry.state = LifecycleState.DEAD;
        }
        try { nativeConfirmDead(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
    }

    public static boolean isDeadConfirmed(UUID uuid) {
        return DEAD_CONFIRMED.contains(uuid);
    }

    public static void clearDeadConfirmed(UUID uuid) {
        DEAD_CONFIRMED.internalRemove(uuid);
        try { nativeClearDeadConfirmed(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
    }

    public static Set<UUID> getDeadConfirmedSet() {
        return DEAD_CONFIRMED;
    }

    public static void setForcedHealth(UUID uuid, float health) {
        FORCED_HEALTH.put(uuid, Float.valueOf(health));
    }

    public static Float getForcedHealth(UUID uuid) {
        return FORCED_HEALTH.get(uuid);
    }

    public static void clearForcedHealth(UUID uuid) {
        FORCED_HEALTH.remove(uuid);
    }

    public static void markDroppedLoot(UUID uuid) {
        LOOT_DROPPED.add(uuid);
    }

    public static boolean hasDroppedLoot(UUID uuid) {
        return LOOT_DROPPED.contains(uuid);
    }

    public static Set<UUID> getKillSet() {
        return KILL_SET;
    }

    public static Set<UUID> getImmortalSet() {
        return IMMORTAL_SET;
    }

    public static void addObjectToKillSet(Object obj) {
        if (obj == null) return;
        int key = System.identityHashCode(obj);
        OBJECT_KILL_SET.put(key, new WeakReference<>(obj));
    }

    public static boolean isObjectInKillSet(Object obj) {
        if (obj == null) return false;
        int key = System.identityHashCode(obj);
        WeakReference<Object> ref = OBJECT_KILL_SET.get(key);
        return ref != null && ref.get() != null;
    }

    public static void registerTickSource(Object obj, TickSource source) {
        if (obj == null || source == null) return;
        int key = System.identityHashCode(obj);
        OBJECT_TICK_SOURCES.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(source);
    }

    public static List<TickSource> getTickSources(Object obj) {
        if (obj == null) return List.of();
        int key = System.identityHashCode(obj);
        List<TickSource> sources = OBJECT_TICK_SOURCES.get(key);
        return sources != null ? sources : List.of();
    }

    public static ConcurrentHashMap<Integer, WeakReference<Object>> getObjectKillSet() {
        return OBJECT_KILL_SET;
    }

    public static ConcurrentHashMap<Integer, List<TickSource>> getAllTickSources() {
        return OBJECT_TICK_SOURCES;
    }

    public static void cleanupDeadObjectRefs() {
        Iterator<Map.Entry<Integer, WeakReference<Object>>> it = OBJECT_KILL_SET.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, WeakReference<Object>> entry = it.next();
            if (entry.getValue().get() == null) {
                it.remove();
                OBJECT_TICK_SOURCES.remove(entry.getKey());
            }
        }
    }

    public static void syncImmortalSetFromBackup() {
        for (UUID uuid : IMMORTAL_SET_BACKUP) {
            if (!IMMORTAL_SET.contains(uuid)) {
                IMMORTAL_SET.add(uuid);
            }
        }
        try { nativeSyncImmortalFromBackup(); } catch (Throwable ignored) {}
    }
}

