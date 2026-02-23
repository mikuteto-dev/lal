package jp.mikumiku.lal.core;

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
    private static final Map<String, List<KillRecord>> KILL_HISTORY = new ConcurrentHashMap<String, List<KillRecord>>();
    private static final double KILL_MATCH_RADIUS_SQ = 1024.0;
    private static final int KILL_MATCH_TICK_WINDOW = 100;
    private static final int KILL_HISTORY_EXPIRY_TICKS = 200;

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

    public static void recordKill(Entity entity, int tick, UUID attackerUuid) {
        String className = entity.getClass().getName();
        KILL_HISTORY.computeIfAbsent(className, k -> new CopyOnWriteArrayList()).add(new KillRecord(className, entity.getX(), entity.getY(), entity.getZ(), tick, attackerUuid));
    }

    public static KillRecord findMatchingKill(Entity newEntity, int currentTick) {
        String className = newEntity.getClass().getName();
        List<KillRecord> records = KILL_HISTORY.get(className);
        if (records == null) {
            return null;
        }
        for (KillRecord record : records) {
            if (currentTick - record.killTick > KILL_MATCH_TICK_WINDOW) continue;
            double dx = newEntity.getX() - record.x;
            double dy = newEntity.getY() - record.y;
            double dz = newEntity.getZ() - record.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > KILL_MATCH_RADIUS_SQ) continue;
            return record;
        }
        return null;
    }

    public static boolean hasKillHistoryForClass(String className) {
        List<KillRecord> records = KILL_HISTORY.get(className);
        return records != null && !records.isEmpty();
    }

    public static void cleanupKillHistory(int currentTick) {
        KILL_HISTORY.values().forEach(list -> list.removeIf(record -> currentTick - record.killTick > KILL_HISTORY_EXPIRY_TICKS));
        KILL_HISTORY.entrySet().removeIf(entry -> ((List)entry.getValue()).isEmpty());
    }

    public static Set<UUID> getKillSet() {
        return KILL_SET;
    }

    public static Set<UUID> getImmortalSet() {
        return IMMORTAL_SET;
    }

    public static void syncImmortalSetFromBackup() {
        for (UUID uuid : IMMORTAL_SET_BACKUP) {
            if (!IMMORTAL_SET.contains(uuid)) {
                IMMORTAL_SET.add(uuid);
            }
        }
        try { nativeSyncImmortalFromBackup(); } catch (Throwable ignored) {}
    }

    public static class KillRecord {
        public final String className;
        public final double x;
        public final double y;
        public final double z;
        public final int killTick;
        public final UUID attackerUuid;

        public KillRecord(String className, double x, double y, double z, int killTick, UUID attackerUuid) {
            super();
            this.className = className;
            this.x = x;
            this.y = y;
            this.z = z;
            this.killTick = killTick;
            this.attackerUuid = attackerUuid;
        }
    }
}

