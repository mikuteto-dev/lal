package jp.mikumiku.lal.enforcement;

import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jp.mikumiku.lal.agent.LALAgentBridge;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import jp.mikumiku.lal.util.FieldAccessUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class EnforcementDaemon {

    private static final long INTERVAL_MS_NORMAL = 50;
    private static final long INTERVAL_MS_FAST = 5;
    private static final int RETRANSFORM_INTERVAL = 200;

    private static volatile boolean running = false;
    private static Thread daemonThread = null;
    private static int loopCount = 0;

    private static final ConcurrentHashMap<UUID, WeakReference<LivingEntity>> trackedEntities = new ConcurrentHashMap<>();

    public static void start() {
        if (running && daemonThread != null && daemonThread.isAlive()) {
            return;
        }
        running = true;
        loopCount = 0;
        daemonThread = new Thread(EnforcementDaemon::run, "LAL-Enforcement");
        daemonThread.setDaemon(true);
        daemonThread.setPriority(Thread.MAX_PRIORITY);
        daemonThread.setUncaughtExceptionHandler((t, e) -> {
            running = false;
            ensureRunning();
        });
        daemonThread.start();
    }

    public static void stop() {
        running = false;
    }

    public static void ensureRunning() {
        try {
            if (running && daemonThread != null && daemonThread.isAlive()) {
                return;
            }
            start();
        } catch (Throwable t) {}
    }

    public static void trackEntity(LivingEntity entity) {
        if (entity == null) return;
        try {
            trackedEntities.put(entity.getUUID(), new WeakReference<>(entity));
        } catch (Throwable t) {
        }
    }

    private static void run() {
        while (running) {
            try {
                long interval = CombatRegistry.getKillSet().isEmpty() ? INTERVAL_MS_NORMAL : INTERVAL_MS_FAST;
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                break;
            }

            try {
                cleanupStaleReferences();
                processEntities();

                try {
                    KillEnforcer.restoreEventBusIfNeeded();
                } catch (Throwable ignored) {}

                loopCount++;
                if (loopCount >= RETRANSFORM_INTERVAL) {
                    loopCount = 0;
                    checkHookCallsAndRetransform();
                }
            } catch (Throwable t) {
            }
        }
    }

    private static void cleanupStaleReferences() {
        try {
            Iterator<Map.Entry<UUID, WeakReference<LivingEntity>>> it = trackedEntities.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, WeakReference<LivingEntity>> entry = it.next();
                if (entry.getValue().get() == null) {
                    it.remove();
                }
            }
        } catch (Throwable t) {
        }
    }

    private static void processEntities() {
        for (Map.Entry<UUID, WeakReference<LivingEntity>> entry : trackedEntities.entrySet()) {
            try {
                LivingEntity entity = entry.getValue().get();
                if (entity == null) continue;

                UUID uuid = entry.getKey();

                if (CombatRegistry.isInImmortalSet(uuid)) {
                    enforceImmortal(entity);
                    enforceMovement(entity);
                } else if (CombatRegistry.isInKillSet(uuid)) {
                    enforceKill(entity);
                }
            } catch (Throwable t) {
            }
        }
    }

    private static final ConcurrentHashMap<String, Object> intDataCache = new ConcurrentHashMap<>();
    private static Field ITEMS_BY_ID_FIELD;
    private static boolean itemsByIdResolved = false;

    private static void enforceImmortal(LivingEntity entity) {
        try {
            float maxHealth = 20.0f;
            try {
                maxHealth = entity.getMaxHealth();
            } catch (Throwable t) {}
            if (maxHealth <= 0.0f) {
                maxHealth = 20.0f;
            }

            if (FieldAccessUtil.HEALTH != null) {
                FieldAccessUtil.HEALTH.set(entity, maxHealth);
            }
            if (FieldAccessUtil.DEAD != null) {
                FieldAccessUtil.DEAD.set(entity, false);
            }
            if (FieldAccessUtil.DEATH_TIME != null) {
                FieldAccessUtil.DEATH_TIME.set(entity, 0);
            }
            if (FieldAccessUtil.HURT_TIME != null) {
                FieldAccessUtil.HURT_TIME.set(entity, 0);
            }
            if (FieldAccessUtil.REMOVAL_REASON != null) {
                try {
                    Entity.RemovalReason reason = (Entity.RemovalReason) FieldAccessUtil.REMOVAL_REASON.get(entity);
                    if (reason != null && entity.isAddedToWorld()) {
                        FieldAccessUtil.REMOVAL_REASON.set(entity, (Entity.RemovalReason) null);
                    }
                } catch (Throwable t) {}
            }

            try {
                entity.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(maxHealth));
            } catch (Throwable t) {}
        } catch (Throwable t) {
        }

        if (entity instanceof Player && LALSwordItem.hasLALEquipment((Player) entity)) {
            try {
                restoreIntegerDataItems(entity);
            } catch (Throwable ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private static void restoreIntegerDataItems(LivingEntity entity) {
        try {
            if (!itemsByIdResolved) {
                itemsByIdResolved = true;
                for (String name : new String[]{"f_135354_", "itemsById"}) {
                    try {
                        ITEMS_BY_ID_FIELD = SynchedEntityData.class.getDeclaredField(name);
                        ITEMS_BY_ID_FIELD.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (ITEMS_BY_ID_FIELD == null) return;

            SynchedEntityData entityData = entity.getEntityData();
            Object itemsById = ITEMS_BY_ID_FIELD.get(entityData);
            if (itemsById == null) return;

            String entityKey = entity.getUUID().toString();
            Iterable<?> items = null;
            if (itemsById.getClass().isArray()) {
                items = java.util.Arrays.asList((Object[]) itemsById);
            } else {
                try {
                    Method valuesMethod = itemsById.getClass().getMethod("values");
                    Object values = valuesMethod.invoke(itemsById);
                    if (values instanceof Iterable) items = (Iterable<?>) values;
                } catch (Throwable ignored) {}
            }
            if (items == null) return;

            for (Object item : items) {
                if (item == null) continue;
                try {
                    Field valueField = null;
                    Field accessorField = null;
                    for (Field f : item.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        if (valueField == null && (f.getName().equals("value") || f.getType() == Object.class)) valueField = f;
                        if (accessorField == null && (f.getName().equals("accessor") || f.getType().getSimpleName().contains("EntityDataAccessor"))) accessorField = f;
                    }
                    if (valueField == null || accessorField == null) continue;
                    Object value = valueField.get(item);
                    if (!(value instanceof Integer)) continue;
                    int intVal = (Integer) value;
                    Object accessor = accessorField.get(item);
                    String cacheKey = entityKey + ":" + accessor.hashCode();
                    Object cached = intDataCache.get(cacheKey);
                    if (intVal <= 0 && cached instanceof Integer && (Integer) cached > 0) {
                        entityData.set((EntityDataAccessor<Integer>) accessor, (Integer) cached);
                    } else if (intVal > 0) {
                        intDataCache.put(cacheKey, intVal);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void enforceKill(LivingEntity entity) {
        try {
            if (FieldAccessUtil.HEALTH != null) {
                FieldAccessUtil.HEALTH.set(entity, 0.0f);
            }
            if (FieldAccessUtil.DEAD != null) {
                FieldAccessUtil.DEAD.set(entity, true);
            }
            if (FieldAccessUtil.DEATH_TIME != null) {
                FieldAccessUtil.DEATH_TIME.set(entity, 20);
            }
            if (FieldAccessUtil.REMOVAL_REASON != null) {
                FieldAccessUtil.REMOVAL_REASON.set(entity, Entity.RemovalReason.KILLED);
            }
        } catch (Throwable t) {
        }
    }

    private static void enforceMovement(LivingEntity entity) {
        try {
            entity.noPhysics = false;
        } catch (Throwable t) {
        }
    }

    private static void checkHookCallsAndRetransform() {
        try {
            long calls = EntityMethodHooks.getAndResetHookCallCount();
            if (calls == 0 && !trackedEntities.isEmpty()) {
                triggerRetransform();
            }
        } catch (Throwable t) {
            triggerRetransform();
        }
    }

    private static void triggerRetransform() {
        try {
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null) return;

            Class<?>[] targets = {
                Entity.class,
                LivingEntity.class,
                Player.class,
                ServerPlayer.class,
                ServerLevel.class
            };

            for (Class<?> target : targets) {
                try {
                    inst.retransformClasses(target);
                } catch (Throwable t) {
                }
            }
        } catch (Throwable t) {
        }
    }
}
