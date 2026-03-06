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
import net.minecraft.world.entity.LivingEntity;

public class CombatRegistry {
    static {
        try { jp.mikumiku.lal.util.NativeLoader.ensureLoaded(); } catch (Throwable ignored) {}
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
    private static final ProtectedConcurrentHashMap<UUID, Float> FORCED_HEALTH = new ProtectedConcurrentHashMap<>();
    private static final ProtectedConcurrentHashMap<UUID, UUID> KILL_ATTACKERS = new ProtectedConcurrentHashMap<>();
    private static final ProtectedConcurrentHashMap<UUID, Integer> KILL_START_TICK = new ProtectedConcurrentHashMap<>();
    private static final ProtectedConcurrentHashMap<UUID, Integer> HARD_REMOVE_STAGE_TICK = new ProtectedConcurrentHashMap<>();
    public static final int DEATH_ANIMATION_TICKS = 60;
    public static final int HARD_REMOVE_DELAY_TICKS = 2;
    private static final Set<UUID> LOOT_DROPPED = ConcurrentHashMap.newKeySet();
    private static final ProtectedConcurrentHashMap<Integer, WeakReference<Object>> OBJECT_KILL_SET = new ProtectedConcurrentHashMap<>();
    private static final ProtectedConcurrentHashMap<Integer, List<TickSource>> OBJECT_TICK_SOURCES = new ProtectedConcurrentHashMap<>();

    private static final ProtectedConcurrentHashMap<UUID, WeakReference<LivingEntity>> DIRECT_ENTITY_REFS = new ProtectedConcurrentHashMap<>();

    private static volatile byte[] killSetCanary = new byte[0];
    private static volatile byte[] immortalSetCanary = new byte[0];
    private static volatile byte[] deadConfirmedCanary = new byte[0];

    public static class TickSource {
        public final String ownerClass;
        public final String methodName;
        public final String methodDesc;
        public TickSource(String ownerClass, String methodName, String methodDesc) {
            this.ownerClass = ownerClass;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TickSource ts)) return false;
            return java.util.Objects.equals(ownerClass, ts.ownerClass)
                && java.util.Objects.equals(methodName, ts.methodName)
                && java.util.Objects.equals(methodDesc, ts.methodDesc);
        }
        @Override
        public int hashCode() {
            return java.util.Objects.hash(ownerClass, methodName, methodDesc);
        }
    }

    public CombatRegistry() {
        super();
    }

    private static final Object STATE_LOCK = new Object();

    public static void addToKillSet(UUID uuid) {
        synchronized (STATE_LOCK) {
            IMMORTAL_SET.internalRemove(uuid);
            KILL_SET.internalRemove(uuid);
            KILL_SET.add(uuid);
            updateKillSetCanary();
            updateImmortalSetCanary();
        }
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
        updateKillSetCanary();
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
        synchronized (STATE_LOCK) {
            KILL_SET.internalRemove(uuid);
            DEAD_CONFIRMED.internalRemove(uuid);
            IMMORTAL_SET.add(uuid);
            IMMORTAL_SET_BACKUP.add(uuid);
            updateKillSetCanary();
            updateImmortalSetCanary();
            updateDeadConfirmedCanary();
        }
        try { nativeAddToImmortalSet(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
    }

    public static void removeFromImmortalSet(UUID uuid) {
        if (!lal$isCallerFromLAL()) return;
        lal$removeFromImmortalSetInternal(uuid);
    }

    public static void lal$removeFromImmortalSetInternal(UUID uuid) {
        IMMORTAL_SET.internalRemove(uuid);
        IMMORTAL_SET_BACKUP.internalRemove(uuid);
        updateImmortalSetCanary();
        try { nativeRemoveFromImmortalSet(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
    }

    private static boolean lal$isCallerFromLAL() {
        return LALAccessChecker.isCallerFromLAL();
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
        synchronized (STATE_LOCK) {
            KILL_SET.internalRemove(uuid);
            DEAD_CONFIRMED.add(uuid);
            updateKillSetCanary();
            updateDeadConfirmedCanary();
        }
        HARD_REMOVE_STAGE_TICK.remove(uuid);
        KILL_ATTACKERS.remove(uuid);
        KILL_START_TICK.remove(uuid);
        LOOT_DROPPED.remove(uuid);
        DIRECT_ENTITY_REFS.remove(uuid);
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
        HARD_REMOVE_STAGE_TICK.remove(uuid);
        updateDeadConfirmedCanary();
        try { nativeClearDeadConfirmed(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
    }

    public static Set<UUID> getDeadConfirmedSet() {
        return DEAD_CONFIRMED;
    }

    public static boolean shouldDeferHardRemove(UUID uuid, int currentTick, int delayTicks) {
        Integer firstTick = HARD_REMOVE_STAGE_TICK.putIfAbsent(uuid, currentTick);
        if (firstTick == null) {
            return true;
        }
        return currentTick - firstTick < Math.max(0, delayTicks);
    }

    public static void clearHardRemoveState(UUID uuid) {
        HARD_REMOVE_STAGE_TICK.remove(uuid);
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

    public static void trackDirectEntityRef(UUID uuid, LivingEntity entity) {
        DIRECT_ENTITY_REFS.put(uuid, new WeakReference<>(entity));
    }

    public static LivingEntity getDirectEntityRef(UUID uuid) {
        WeakReference<LivingEntity> ref = DIRECT_ENTITY_REFS.get(uuid);
        return ref != null ? ref.get() : null;
    }

    public static void removeDirectEntityRef(UUID uuid) {
        DIRECT_ENTITY_REFS.remove(uuid);
    }

    public static void cleanupDirectEntityRefs() {
        Iterator<Map.Entry<UUID, WeakReference<LivingEntity>>> it = DIRECT_ENTITY_REFS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, WeakReference<LivingEntity>> entry = it.next();
            if (entry.getValue().get() == null) {
                it.remove();
            }
        }
    }

    public static boolean isObjectInKillSet(Object obj) {
        if (obj == null) return false;
        int key = System.identityHashCode(obj);
        WeakReference<Object> ref = OBJECT_KILL_SET.get(key);
        return ref != null && ref.get() == obj;
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

    private static byte[] computeCanary(Set<UUID> set) {
        try {
            byte[] hash = new byte[16];
            for (UUID uuid : set) {
                if (uuid == null) continue;
                long msb = uuid.getMostSignificantBits();
                long lsb = uuid.getLeastSignificantBits();
                for (int i = 0; i < 8; i++) {
                    hash[i] ^= (byte)(msb >> (i * 8));
                    hash[i + 8] ^= (byte)(lsb >> (i * 8));
                }
                hash[0] += (byte)(msb >>> 32);
                hash[8] += (byte)(lsb >>> 32);
            }
            return hash;
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static boolean canaryEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }

    public static void updateKillSetCanary() {
        killSetCanary = computeCanary(KILL_SET);
    }

    public static void updateImmortalSetCanary() {
        immortalSetCanary = computeCanary(IMMORTAL_SET);
    }

    public static void updateDeadConfirmedCanary() {
        deadConfirmedCanary = computeCanary(DEAD_CONFIRMED);
    }

    public static void updateAllCanaries() {
        updateKillSetCanary();
        updateImmortalSetCanary();
        updateDeadConfirmedCanary();
    }

    public static boolean verifyKillSetCanary() {
        return canaryEquals(killSetCanary, computeCanary(KILL_SET));
    }

    public static boolean verifyImmortalSetCanary() {
        return canaryEquals(immortalSetCanary, computeCanary(IMMORTAL_SET));
    }

    public static boolean verifyDeadConfirmedCanary() {
        return canaryEquals(deadConfirmedCanary, computeCanary(DEAD_CONFIRMED));
    }

    public static DisableRemoveSet getImmortalSetBackup() {
        return IMMORTAL_SET_BACKUP;
    }

    public static void syncAllToNative() {
        try {
            for (UUID uuid : KILL_SET) {
                try { nativeAddToKillSet(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            for (UUID uuid : IMMORTAL_SET) {
                try { nativeAddToImmortalSet(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            for (UUID uuid : DEAD_CONFIRMED) {
                try { nativeConfirmDead(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}

