package jp.mikumiku.lal.entity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LALEntityClientStateStore {

    static final ConcurrentHashMap<UUID, LALEntityClientState> states = new ConcurrentHashMap<>();
    static final ConcurrentLinkedQueue<double[]> pendingEffects = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<UUID, LALEntityClientState> backup = new ConcurrentHashMap<>();
    private static volatile boolean suppressRecovery = false;

    private static boolean isCallerFromLAL() {
        return jp.mikumiku.lal.core.LALAccessChecker.isCallerFromLAL();
    }

    public static void update(UUID id, double x, double y, double z, float rotX, float rotY, int attackMode, float walkAnimSpeed, int attackTimer) {
        try {
            LALEntityClientState state = states.get(id);
            if (state != null) {
                state.updateFromPacket(x, y, z, rotX, rotY, attackMode, walkAnimSpeed, attackTimer);
                backup.put(id, state);
            } else {
                LALEntityClientState newState = new LALEntityClientState(id, x, y, z);
                newState.rotX = rotX;
                newState.rotY = rotY;
                newState.attackMode = attackMode;
                newState.walkAnimSpeed = walkAnimSpeed;
                newState.attackTimer = attackTimer;
                states.put(id, newState);
                backup.put(id, newState);
            }
        } catch (Throwable ignored) {}
    }

    public static void add(UUID id, double x, double y, double z) {
        try {
            suppressRecovery = false;
            LALEntityClientState newState = new LALEntityClientState(id, x, y, z);
            states.put(id, newState);
            backup.put(id, newState);
        } catch (Throwable ignored) {}
    }

    public static void remove(UUID id) {
        try {
            if (!isCallerFromLAL()) return;
            states.remove(id);
            backup.remove(id);
            if (states.isEmpty()) {
                suppressRecovery = true;
            }
        } catch (Throwable ignored) {}
    }

    public static void addEffect(int type, double x, double y, double z, double tx, double ty, double tz) {
        try {
            pendingEffects.add(new double[]{type, x, y, z, tx, ty, tz});
        } catch (Throwable ignored) {}
    }

    public static void clear() {
        if (!isCallerFromLAL()) return;
        states.clear();
        backup.clear();
        pendingEffects.clear();
        suppressRecovery = true;
    }

    public static void verifyIntegrity() {
        try {
            if (suppressRecovery) return;
            if (states.isEmpty() && !backup.isEmpty()) {
                states.putAll(backup);
            }
        } catch (Throwable ignored) {}
    }
}
