package jp.mikumiku.lal.core;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BreakRegistry {

    private static final ConcurrentHashMap<UUID, BreakState> STATES = new ConcurrentHashMap<>();

    private static final int TIMEOUT_TICKS = 600;

    public static class BreakState {
        public volatile float healthCap;
        public volatile long lastAttackTick;
        public volatile boolean finalized;

        public BreakState(float healthCap, long tick) {
            this.healthCap = healthCap;
            this.lastAttackTick = tick;
            this.finalized = false;
        }
    }

    public static void registerHit(UUID uuid, float currentTrueHealth, float damage, long tick) {
        BreakState state = STATES.get(uuid);
        if (state == null) {
            float newCap = Math.max(currentTrueHealth - damage, 0.0f);
            STATES.put(uuid, new BreakState(newCap, tick));
        } else {
            float effectiveHealth = Math.min(currentTrueHealth, state.healthCap);
            state.healthCap = Math.max(effectiveHealth - damage, 0.0f);
            state.lastAttackTick = tick;
        }
    }

    public static BreakState getState(UUID uuid) {
        return STATES.get(uuid);
    }

    public static boolean isBreaking(UUID uuid) {
        return STATES.containsKey(uuid);
    }

    public static float getHealthCap(UUID uuid) {
        BreakState state = STATES.get(uuid);
        return state != null ? state.healthCap : Float.MAX_VALUE;
    }

    public static Set<UUID> getBreakingUuids() {
        return STATES.keySet();
    }

    public static void remove(UUID uuid) {
        STATES.remove(uuid);
    }

    public static void cleanup(long currentTick) {
        STATES.entrySet().removeIf(entry -> {
            BreakState state = entry.getValue();
            return (currentTick - state.lastAttackTick) > TIMEOUT_TICKS;
        });
    }

    public static void clear() {
        STATES.clear();
    }
}
