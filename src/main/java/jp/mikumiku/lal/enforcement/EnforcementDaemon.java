package jp.mikumiku.lal.enforcement;

import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

import jp.mikumiku.lal.LifeAuthorityLayer;
import jp.mikumiku.lal.agent.LALAgent;
import jp.mikumiku.lal.agent.LALAgentBridge;
import jp.mikumiku.lal.core.BreakRegistry;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.enforcement.LALEntityRemover;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import jp.mikumiku.lal.util.FieldAccessUtil;
import jp.mikumiku.lal.util.MixinUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class EnforcementDaemon {

    private static final long INTERVAL_MS_NORMAL = 50;
    private static final long INTERVAL_MS_FAST = 5;
    private static final long INTERVAL_MS_ESCALATED = 1;
    private static final long INTERVAL_MS_IMMORTAL = 25;
    private static volatile long escalationUntil = 0;
    private static volatile int retransformInterval = 200;
    private static volatile int consecutiveHookFailures = 0;

    private static volatile boolean running = false;
    private static Thread daemonThread = null;
    private static int loopCount = 0;

    private static volatile boolean poolDaemonRunning = false;
    private static final AtomicLong poolDaemonHeartbeat = new AtomicLong(0);
    private static final AtomicLong mainDaemonHeartbeat = new AtomicLong(0);

    private static final ConcurrentHashMap<UUID, WeakReference<LivingEntity>> trackedEntities = new ConcurrentHashMap<>();

    private static final Set<UUID> localKillSetBackup = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> localImmortalSetBackup = ConcurrentHashMap.newKeySet();
    private static volatile Class<?> expectedEventBusClass;

    private static final ConcurrentHashMap<UUID, int[]> tickCountTracker = new ConcurrentHashMap<>();
    private static final int TICK_STALL_THRESHOLD = 3;

    public static void start() {
        if (running && daemonThread != null && daemonThread.isAlive()) {
            return;
        }
        running = true;
        loopCount = 0;
        daemonThread = new Thread(EnforcementDaemon::run, "Thread-" + UUID.randomUUID().toString().substring(0, 8)) {
            @Override
            public void interrupt() {
                if (!jp.mikumiku.lal.core.LALAccessChecker.isCallerFromLAL()) return;
                super.interrupt();
            }
            @Override
            public boolean isInterrupted() {
                if (!jp.mikumiku.lal.core.LALAccessChecker.isCallerFromLAL()) return false;
                return super.isInterrupted();
            }
            @Override
            public StackTraceElement[] getStackTrace() {
                if (!jp.mikumiku.lal.core.LALAccessChecker.isCallerFromLAL()) return new StackTraceElement[0];
                return super.getStackTrace();
            }
        };
        daemonThread.setDaemon(true);
        daemonThread.setPriority(Thread.MAX_PRIORITY);
        daemonThread.setUncaughtExceptionHandler((t, e) -> {
            running = false;
            ensureRunning();
        });
        daemonThread.start();
        try {
            if (expectedEventBusClass == null) {
                expectedEventBusClass = net.minecraftforge.common.MinecraftForge.EVENT_BUS.getClass();
            }
        } catch (Throwable ignored) {}
        ensurePoolDaemonRunning();
    }

    public static void stop() {
        running = false;
    }

    public static void escalate() {
        escalationUntil = System.currentTimeMillis() + 5000;
        try { triggerRetransform(); } catch (Throwable ignored) {}
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
                try {
                    long interval;
                    if (System.currentTimeMillis() < escalationUntil) {
                        interval = INTERVAL_MS_ESCALATED;
                    } else if (!CombatRegistry.getKillSet().isEmpty()) {
                        interval = INTERVAL_MS_FAST;
                    } else if (!CombatRegistry.getImmortalSet().isEmpty()) {
                        interval = INTERVAL_MS_IMMORTAL;
                    } else {
                        interval = INTERVAL_MS_NORMAL;
                    }
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    continue;
                }

                try {
                    Thread.interrupted();

                    try {
                        if (expectedEventBusClass != null) {
                            Class<?> currentClass = net.minecraftforge.common.MinecraftForge.EVENT_BUS.getClass();
                            if (currentClass != expectedEventBusClass) {
                                KillEnforcer.restoreEventBusIfNeeded();
                            }
                        }
                    } catch (Throwable ignored) {}

                    try {
                        resetGlobalDisableFlags();
                    } catch (Throwable ignored) {}

                    cleanupStaleReferences();
                    processEntities();

                    if (loopCount % 100 == 50) {
                        try {
                            detectAndNeutralizeGhostEntities();
                        } catch (Throwable ignored) {}
                    }

                    try {
                        ObjectLinker.purgeKilledObjectsFromCollections();
                    } catch (Throwable ignored) {}

                    try {
                        ObjectKillEnforcer.processAll();
                    } catch (Throwable ignored) {}

                    try {
                        KillEnforcer.restoreEventBusIfNeeded();
                    } catch (Throwable ignored) {}

                    try {
                        CombatRegistry.cleanupDeadObjectRefs();
                    } catch (Throwable ignored) {}

                    try {
                        CombatRegistry.cleanupDirectEntityRefs();
                    } catch (Throwable ignored) {}

                    try {
                        verifyCanaryValues();
                    } catch (Throwable ignored) {}

                    if (loopCount % 200 == 0) {
                        try {
                            scanThreadLocals();
                        } catch (Throwable ignored) {}
                    }

                    if (loopCount % 500 == 0) {
                        try {
                            deepScanAllClasses();
                        } catch (Throwable ignored) {}
                    }

                    mainDaemonHeartbeat.set(System.currentTimeMillis());
                    if (loopCount % 20 == 10) {
                        try { ensurePoolDaemonRunning(); } catch (Throwable ignored) {}
                    }

                    loopCount++;
                    if (loopCount >= retransformInterval) {
                        loopCount = 0;
                        checkHookCallsAndRetransform();
                        CombatRegistry.syncImmortalSetFromBackup();
                        syncLocalBackups();
                        try {
                            LALAgent.syncHiddenBackups(CombatRegistry.getKillSet(), CombatRegistry.getImmortalSet());
                        } catch (Throwable ignored) {}
                        try {
                            LALAgent.syncToHiddenClassStorage(CombatRegistry.getKillSet(), CombatRegistry.getImmortalSet(), CombatRegistry.getDeadConfirmedSet());
                        } catch (Throwable ignored) {}
                        try {
                            LALAgent.syncToBootstrap(CombatRegistry.getKillSet(), CombatRegistry.getImmortalSet(), CombatRegistry.getDeadConfirmedSet());
                        } catch (Throwable ignored) {}
                        try {
                            LALAgentBridge.verifyAndRestore();
                        } catch (Throwable ignored) {}
                        try {
                            verifyEntityManager();
                        } catch (Throwable ignored) {}
                        try {
                            LifeAuthorityLayer.verifyFileSystemProviders();
                        } catch (Throwable ignored) {}
                        try {
                            monitorHiddenClasses();
                        } catch (Throwable ignored) {}
                        try {
                            DynamicTickRemover.applyPending();
                        } catch (Throwable ignored) {}
                        try {
                            jp.mikumiku.lal.entity.LALEntityManager.verifyFromSavedData();
                        } catch (Throwable ignored) {}
                        try {
                            EntityMethodHooks.cleanupKillSignatures();
                        } catch (Throwable ignored) {}
                        try {
                            LALAgent.reRegisterTransformer();
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                }
            } catch (ThreadDeath td) {
                continue;
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
                    tickCountTracker.remove(entry.getKey());
                }
            }
        } catch (Throwable t) {
        }
        try {
            tickCountTracker.entrySet().removeIf(e -> !trackedEntities.containsKey(e.getKey()));
        } catch (Throwable ignored) {}
    }

    private static void processEntities() {
        for (Map.Entry<UUID, WeakReference<LivingEntity>> entry : new java.util.ArrayList<>(trackedEntities.entrySet())) {
            try {
                LivingEntity entity = entry.getValue().get();
                if (entity == null) continue;

                UUID uuid = entry.getKey();

                if (CombatRegistry.isInImmortalSet(uuid)) {
                    enforceImmortal(entity);
                    enforceMovement(entity);
                    enforceNotRemoved(entity);
                    enforceLevelCallback(entity);
                    try {
                        if (entity.level() instanceof ServerLevel sl) {
                            ImmortalEnforcer.ensureInTickList(entity, sl);
                        }
                    } catch (Throwable ignored) {}
                    try {
                        if (entity.level() instanceof ServerLevel sl) {
                            ImmortalEnforcer.ensureEntityRegistration(entity, sl);
                        }
                    } catch (Throwable ignored) {}
                    try {
                        detectAndForceTickIfSkipped(entity, uuid);
                    } catch (Throwable ignored) {}
                } else if (CombatRegistry.isInKillSet(uuid)) {
                    enforceKill(entity);
                }

                if (BreakRegistry.isBreaking(uuid)) {
                    BreakEnforcer.enforce(entity);
                }
            } catch (Throwable t) {
            }
        }
    }

    private static void detectAndForceTickIfSkipped(LivingEntity entity, UUID uuid) {
        int currentTickCount = entity.tickCount;
        int[] tracker = tickCountTracker.computeIfAbsent(uuid, k -> new int[]{currentTickCount, 0});
        if (currentTickCount == tracker[0]) {
            tracker[1]++;
            if (tracker[1] >= TICK_STALL_THRESHOLD) {
                tracker[1] = 0;
                try {
                    EntityMethodHooks.setBypass(true);
                    try {
                        entity.baseTick();
                    } finally {
                        EntityMethodHooks.setBypass(false);
                    }
                } catch (Throwable ignored) {}
            }
        } else {
            tracker[0] = currentTickCount;
            tracker[1] = 0;
        }
    }

    private static final ConcurrentHashMap<String, Object> intDataCache = new ConcurrentHashMap<>();
    private static Field ITEMS_BY_ID_FIELD;
    private static boolean itemsByIdResolved = false;

    private static void enforceImmortal(LivingEntity entity) {
        try {
            float maxHealth = 20.0f;
            try {
                maxHealth = MixinUtil.safeMaxHealth(entity);
            } catch (Throwable t) {}

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
            try {
                EntityMethodHooks.setBypass(true);
                try { entity.setInvulnerable(true); }
                finally { EntityMethodHooks.setBypass(false); }
            } catch (Throwable ignored) {}
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

            try {
                float dataItemHealth = KillEnforcer.readDataItemValue(
                        entity.getEntityData(), LivingEntity.DATA_HEALTH_ID);
                if (!Float.isNaN(dataItemHealth) && dataItemHealth <= 0.0f) {
                    KillEnforcer.directWriteDataItem(
                            entity.getEntityData(), LivingEntity.DATA_HEALTH_ID,
                            Float.valueOf(maxHealth));
                    escalate();
                }
            } catch (Throwable ignored) {}

            try {
                float fieldHealth = maxHealth;
                if (FieldAccessUtil.HEALTH != null) {
                    fieldHealth = (float) FieldAccessUtil.HEALTH.get(entity);
                }
                float methodHealth;
                EntityMethodHooks.setBypass(true);
                try { methodHealth = entity.getHealth(); }
                finally { EntityMethodHooks.setBypass(false); }
                if (fieldHealth > 0.0f && methodHealth != fieldHealth) {
                    triggerRetransform();
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
        }

        if (entity instanceof Player && LALSwordItem.hasLALEquipment((Player) entity)) {
            try {
                restoreIntegerDataItems(entity);
            } catch (Throwable ignored) {}
            try {
                ImmortalEnforcer.ensurePositiveIntegerData(entity);
            } catch (Throwable ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private static void restoreIntegerDataItems(LivingEntity entity) {
        try {
            if (!itemsByIdResolved) {
                itemsByIdResolved = true;
                for (String name : new String[]{"f_135345_", "itemsById"}) {
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
            entity.noPhysics = true;
            try {
                EntityMethodHooks.setBypass(true);
                try {
                    entity.setNoGravity(true);
                    entity.setSilent(true);
                    entity.setInvulnerable(false);
                } finally { EntityMethodHooks.setBypass(false); }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
        }
        try {
            EntityMethodHooks.setBypass(true);
            float reportedHealth;
            try { reportedHealth = entity.getHealth(); }
            finally { EntityMethodHooks.setBypass(false); }
            if (reportedHealth > 0.0f) {
                triggerRetransform();
            }
        } catch (Throwable ignored) {}
        if (loopCount % 5 == 0) {
            try { corruptShadowHealth(entity); } catch (Throwable ignored) {}
        }
        if (loopCount % 10 == 0) {
            try {
                if (entity.level() instanceof ServerLevel sl) {
                    LALEntityRemover.deleteFromLevel((Entity) entity, sl);
                }
            } catch (Throwable ignored) {}
            try {
                if (entity.level() instanceof ServerLevel sl) {
                    KillEnforcer.sendRemovePacketToAllPlayers((Entity) entity, sl);
                }
            } catch (Throwable ignored) {}
        }
        if (loopCount % 20 == 0) {
            try { ObjectKillEnforcer.neutralizeSingle(entity); } catch (Throwable ignored) {}
        }
    }

    private static void corruptShadowHealth(LivingEntity entity) {
        float fieldHealth = 0.0f;
        try {
            if (FieldAccessUtil.HEALTH != null) {
                fieldHealth = (float) FieldAccessUtil.HEALTH.get(entity);
            }
        } catch (Throwable ignored) {}

        float methodHealth = 0.0f;
        try {
            EntityMethodHooks.setBypass(true);
            try { methodHealth = entity.getHealth(); }
            finally { EntityMethodHooks.setBypass(false); }
        } catch (Throwable ignored) {}

        if (fieldHealth <= 0.0f && methodHealth > 0.0f) {
            try { corruptExternalHealthStorage(entity); } catch (Throwable ignored) {}
        }

        try {
            entity.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void corruptExternalHealthStorage(LivingEntity entity) {
        try {
            java.lang.instrument.Instrumentation inst = jp.mikumiku.lal.agent.LALAgentBridge.getInstrumentation();
            if (inst == null) return;
            String entityPackage = entity.getClass().getPackageName();
            if (entityPackage.startsWith("net.minecraft.") || entityPackage.startsWith("jp.mikumiku.lal.")) return;
            String modPrefix = entityPackage.contains(".") ?
                    entityPackage.substring(0, entityPackage.indexOf('.', entityPackage.indexOf('.') + 1) + 1) :
                    entityPackage;
            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                try {
                    if (!clazz.getName().startsWith(modPrefix)) continue;
                    for (java.lang.reflect.Field f : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        try {
                            f.setAccessible(true);
                            Object val = f.get(null);
                            if (val == null) continue;
                            if (val instanceof java.util.WeakHashMap) {
                                java.util.WeakHashMap<Object, Object> whm = (java.util.WeakHashMap<Object, Object>) val;
                                if (whm.containsKey(entity)) {
                                    Object hpVal = whm.get(entity);
                                    if (hpVal instanceof Float || hpVal instanceof Double || hpVal instanceof Number) {
                                        whm.put(entity, 0.0f);
                                    }
                                }
                            } else if (val instanceof java.util.concurrent.ConcurrentHashMap) {
                                java.util.concurrent.ConcurrentHashMap<Object, Object> chm =
                                        (java.util.concurrent.ConcurrentHashMap<Object, Object>) val;
                                if (chm.containsKey(entity)) {
                                    Object hpVal = chm.get(entity);
                                    if (hpVal instanceof Float || hpVal instanceof Double || hpVal instanceof Number) {
                                        chm.put(entity, 0.0f);
                                    }
                                }
                                UUID uuid = entity.getUUID();
                                if (chm.containsKey(uuid)) {
                                    Object hpVal = chm.get(uuid);
                                    if (hpVal instanceof Float || hpVal instanceof Double || hpVal instanceof Number) {
                                        chm.put(uuid, 0.0f);
                                    }
                                }
                            } else if (val instanceof java.util.Map) {
                                java.util.Map<Object, Object> map = (java.util.Map<Object, Object>) val;
                                try {
                                    if (map.containsKey(entity)) {
                                        Object hpVal = map.get(entity);
                                        if (hpVal instanceof Float || hpVal instanceof Double || hpVal instanceof Number) {
                                            map.put(entity, 0.0f);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void enforceMovement(LivingEntity entity) {
        try {
            entity.noPhysics = false;
        } catch (Throwable t) {
        }
    }

    private static void enforceNotRemoved(LivingEntity entity) {
        try {
            if (FieldAccessUtil.REMOVAL_REASON != null) {
                Entity.RemovalReason reason = (Entity.RemovalReason) FieldAccessUtil.REMOVAL_REASON.get(entity);
                if (reason != null) {
                    FieldAccessUtil.REMOVAL_REASON.set(entity, (Entity.RemovalReason) null);
                }
            }
        } catch (Throwable t) {}
    }

    private static Field levelCallbackField = null;
    private static boolean levelCallbackFieldResolved = false;

    private static Field entityManagerField = null;
    private static boolean entityManagerFieldResolved = false;
    private static Object originalEntityManager = null;
    private static WeakReference<ServerLevel> trackedServerLevel = new WeakReference<>(null);

    private static final java.util.List<ResetTarget> globalFlagTargets = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean flagTargetsScanned = false;
    private static volatile int lastHiddenClassCount = -1;

    private static class ResetTarget {
        final Class<?> clazz;
        final Field field;
        ResetTarget(Class<?> clazz, Field field) {
            this.clazz = clazz;
            this.field = field;
        }
    }

    private static void enforceLevelCallback(LivingEntity entity) {
        try {
            if (!levelCallbackFieldResolved) {
                levelCallbackFieldResolved = true;
                for (String name : new String[]{"f_146801_", "levelCallback"}) {
                    try {
                        levelCallbackField = Entity.class.getDeclaredField(name);
                        levelCallbackField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (levelCallbackField == null) return;
            Object cb = levelCallbackField.get(entity);
            boolean isNull = (cb == null);
            boolean isNullCb = false;
            if (!isNull) {
                try {
                    isNullCb = (cb == EntityInLevelCallback.NULL);
                } catch (Throwable ignored) {}
            }
            if (isNull || isNullCb) {
                try {
                    ServerLevel sl = null;
                    try {
                        if (entity.level() instanceof ServerLevel) {
                            sl = (ServerLevel) entity.level();
                        }
                    } catch (Throwable ignored) {}
                    if (sl == null) {
                        sl = trackedServerLevel.get();
                    }
                    if (sl == null) return;
                    resolveEntityManagerField();
                    if (entityManagerField == null) return;
                    Object mgr = entityManagerField.get(sl);
                    if (mgr == null) return;
                    for (String name : new String[]{"f_157492_", "callbacks", "levelCallback"}) {
                        try {
                            Field cbField = mgr.getClass().getDeclaredField(name);
                            cbField.setAccessible(true);
                            Object cbImpl = cbField.get(mgr);
                            if (cbImpl != null && cbImpl instanceof EntityInLevelCallback) {
                                levelCallbackField.set(entity, cbImpl);
                                break;
                            }
                        } catch (NoSuchFieldException ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {}
    }

    private static void checkHookCallsAndRetransform() {
        try {
            long calls = EntityMethodHooks.getAndResetHookCallCount();
            if (calls == 0 && !trackedEntities.isEmpty()) {
                consecutiveHookFailures++;
                if (consecutiveHookFailures >= 5) {
                    retransformInterval = 1;
                } else if (consecutiveHookFailures >= 2) {
                    retransformInterval = 5;
                }
                triggerRetransform();
                escalate();
            } else {
                if (consecutiveHookFailures > 0) {
                    consecutiveHookFailures = 0;
                    retransformInterval = 200;
                }
            }
        } catch (Throwable t) {
            consecutiveHookFailures++;
            triggerRetransform();
        }
    }

    private static void verifyCanaryValues() {
        boolean tampered = false;
        if (!CombatRegistry.verifyKillSetCanary()) {
            restoreKillSetFromBackups();
            CombatRegistry.updateKillSetCanary();
            tampered = true;
        }
        if (!CombatRegistry.verifyImmortalSetCanary()) {
            restoreImmortalSetFromBackups();
            CombatRegistry.updateImmortalSetCanary();
            tampered = true;
        }
        if (!CombatRegistry.verifyDeadConfirmedCanary()) {
            CombatRegistry.updateDeadConfirmedCanary();
            tampered = true;
        }
        if (tampered) {
            resolveStorageConflicts();
            escalate();
        }
    }

    private static void resolveStorageConflicts() {
        try {
            Set<UUID> killSet = CombatRegistry.getKillSet();
            Set<UUID> immortalSet = CombatRegistry.getImmortalSet();
            Set<UUID> deadConfirmed = CombatRegistry.getDeadConfirmedSet();
            for (UUID uuid : new HashSet<>(immortalSet)) {
                if (killSet.contains(uuid)) {
                    CombatRegistry.lal$removeFromImmortalSetInternal(uuid);
                }
                if (deadConfirmed.contains(uuid)) {
                    CombatRegistry.lal$removeFromImmortalSetInternal(uuid);
                }
            }
            for (UUID uuid : new HashSet<>(killSet)) {
                if (deadConfirmed.contains(uuid)) {
                    killSet.remove(uuid);
                }
            }
            CombatRegistry.updateAllCanaries();
            try {
                LALAgent.syncHiddenBackups(killSet, immortalSet);
            } catch (Throwable ignored) {}
            try {
                LALAgent.syncToHiddenClassStorage(killSet, immortalSet, deadConfirmed);
            } catch (Throwable ignored) {}
            try {
                LALAgent.syncToBootstrap(killSet, immortalSet, deadConfirmed);
            } catch (Throwable ignored) {}
            try {
                CombatRegistry.syncAllToNative();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void syncLocalBackups() {
        try {
            localKillSetBackup.clear();
            for (UUID uuid : CombatRegistry.getKillSet()) {
                localKillSetBackup.add(uuid);
            }
            localImmortalSetBackup.clear();
            for (UUID uuid : CombatRegistry.getImmortalSet()) {
                localImmortalSetBackup.add(uuid);
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreKillSetFromBackups() {
        try {
            Set<UUID> mainSet = CombatRegistry.getKillSet();
            Set<UUID> hiddenSet = LALAgent.getHiddenKillSetBackup();
            Set<UUID> hsSet = null;
            try { hsSet = LALAgent.getHiddenClassKillSet(); } catch (Throwable ignored) {}
            Set<UUID> bsSet = null;
            try { bsSet = LALAgent.getBootstrapKillSet(); } catch (Throwable ignored) {}
            int mainSize = mainSet.size();
            int localSize = localKillSetBackup.size();
            int hiddenSize = hiddenSet != null ? hiddenSet.size() : 0;
            int hsSize = hsSet != null ? hsSet.size() : 0;
            int bsSize = bsSet != null ? bsSet.size() : 0;

            int maxSize = Math.max(Math.max(Math.max(mainSize, localSize), Math.max(hiddenSize, hsSize)), bsSize);
            Set<UUID> largest = mainSet;
            if (bsSize == maxSize && bsSet != null) {
                largest = bsSet;
            } else if (hsSize == maxSize && hsSet != null) {
                largest = hsSet;
            } else if (localSize == maxSize) {
                largest = localKillSetBackup;
            } else if (hiddenSize == maxSize && hiddenSet != null) {
                largest = hiddenSet;
            }

            Set<UUID> deadConfirmed = CombatRegistry.getDeadConfirmedSet();
            if (largest != mainSet) {
                for (UUID uuid : largest) {
                    if (deadConfirmed.contains(uuid)) continue;
                    if (!mainSet.contains(uuid)) mainSet.add(uuid);
                }
            }
            for (UUID uuid : mainSet) {
                localKillSetBackup.add(uuid);
                if (hiddenSet != null) hiddenSet.add(uuid);
            }
        } catch (Throwable ignored) {}
    }

    public static void registerServerLevel(ServerLevel level) {
        if (level == null) return;
        trackedServerLevel = new WeakReference<>(level);
        try {
            resolveEntityManagerField();
            if (entityManagerField != null) {
                Object mgr = entityManagerField.get(level);
                if (mgr != null && mgr.getClass() == PersistentEntitySectionManager.class) {
                    originalEntityManager = mgr;
                }
            }
        } catch (Throwable ignored) {}
        try {
            CombatRegistry.syncAllToNative();
        } catch (Throwable ignored) {}
    }

    private static void resolveEntityManagerField() {
        if (entityManagerFieldResolved) return;
        entityManagerFieldResolved = true;
        try {
            for (String name : new String[]{"f_143244_", "entityManager"}) {
                try {
                    entityManagerField = ServerLevel.class.getDeclaredField(name);
                    entityManagerField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    public static void ensureLALEntityManager(ServerLevel level) {
        try {
            resolveEntityManagerField();
            if (entityManagerField == null) return;
            Object mgr = entityManagerField.get(level);
            if (mgr == null) return;
            if (mgr instanceof LALPersistentEntitySectionManager) return;
            LALPersistentEntitySectionManager lalMgr = LALPersistentEntitySectionManager.wrapExisting(
                    (PersistentEntitySectionManager<Entity>) mgr);
            if (lalMgr != null) {
                entityManagerField.set(level, lalMgr);
                if (mgr.getClass() == PersistentEntitySectionManager.class) {
                    originalEntityManager = mgr;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void verifyEntityManager() {
        try {
            ServerLevel level = trackedServerLevel.get();
            if (level == null) return;
            resolveEntityManagerField();
            if (entityManagerField == null) return;

            Object currentManager = entityManagerField.get(level);
            if (currentManager == null) return;

            Class<?> managerClass = currentManager.getClass();
            boolean isLAL = currentManager instanceof LALPersistentEntitySectionManager;
            if (!isLAL && managerClass != PersistentEntitySectionManager.class
                    && !managerClass.getName().startsWith("jp.mikumiku.lal.")) {
                if (originalEntityManager != null) {
                    copyManagerFields(currentManager, originalEntityManager);
                    entityManagerField.set(level, originalEntityManager);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void copyManagerFields(Object from, Object to) {
        try {
            Class<?> clazz = PersistentEntitySectionManager.class;
            for (Field f : clazz.getDeclaredFields()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(from);
                    if (val != null) {
                        f.set(to, val);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void detectAndNeutralizeGhostEntities() {
        try {
            ConcurrentHashMap<UUID, java.lang.ref.WeakReference<Entity>> constructed = EntityMethodHooks.getConstructedEntities();
            ConcurrentHashMap<UUID, Long> times = EntityMethodHooks.getConstructedEntityTimes();
            long now = System.nanoTime();

            for (Map.Entry<UUID, java.lang.ref.WeakReference<Entity>> entry : constructed.entrySet()) {
                try {
                    UUID uuid = entry.getKey();
                    java.lang.ref.WeakReference<Entity> ref = entry.getValue();
                    if (ref == null) continue;
                    Entity entity = ref.get();
                    if (entity == null) continue;

                    if (CombatRegistry.isInImmortalSet(uuid)) continue;
                    if (CombatRegistry.isInKillSet(uuid)) continue;
                    if (CombatRegistry.isDeadConfirmed(uuid)) continue;
                    if (entity instanceof Player) continue;

                    try {
                        if (entity.level() != null && entity.level().isClientSide()) continue;
                    } catch (Throwable ignored) {}

                    Long createdAt = times.get(uuid);
                    if (createdAt != null && now - createdAt < 10_000_000_000L) continue;

                    if (!entity.isAddedToWorld() && !entity.isRemoved()) {
                        if (entity instanceof LivingEntity living) {
                            CombatRegistry.addToKillSet(uuid);
                            CombatRegistry.setForcedHealth(uuid, 0.0f);
                            trackEntity(living);
                            try {
                                if (FieldAccessUtil.HEALTH != null) FieldAccessUtil.HEALTH.set(living, 0.0f);
                                if (FieldAccessUtil.DEAD != null) FieldAccessUtil.DEAD.set(living, true);
                            } catch (Throwable ignored) {}
                        }
                        try {
                            removeFromStaticCollections(entity);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void removeFromStaticCollections(Entity entity) {
        try {
            for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    String className = c.getName();
                    if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.") || className.startsWith("jp.mikumiku.lal.")) continue;
                    for (Field f : c.getDeclaredFields()) {
                        try {
                            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            f.setAccessible(true);
                            Object value = f.get(null);
                            if (value instanceof java.util.Collection) {
                                ((java.util.Collection<?>) value).remove(entity);
                            } else if (value instanceof java.util.Map) {
                                ((java.util.Map<?, ?>) value).values().remove(entity);
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreImmortalSetFromBackups() {
        try {
            Set<UUID> mainSet = CombatRegistry.getImmortalSet();
            Set<UUID> backupSet = CombatRegistry.getImmortalSetBackup();
            Set<UUID> hiddenSet = LALAgent.getHiddenImmortalSetBackup();
            Set<UUID> hsSet = null;
            try { hsSet = LALAgent.getHiddenClassImmortalSet(); } catch (Throwable ignored) {}
            Set<UUID> bsSet = null;
            try { bsSet = LALAgent.getBootstrapImmortalSet(); } catch (Throwable ignored) {}
            int mainSize = mainSet.size();
            int backupSize = backupSet.size();
            int localSize = localImmortalSetBackup.size();
            int hiddenSize = hiddenSet != null ? hiddenSet.size() : 0;
            int hsSize = hsSet != null ? hsSet.size() : 0;
            int bsSize = bsSet != null ? bsSet.size() : 0;

            Set<UUID> majority = null;
            int maxSize = Math.max(Math.max(Math.max(mainSize, backupSize), Math.max(localSize, hiddenSize)), Math.max(hsSize, bsSize));
            if (bsSize == maxSize && bsSet != null) {
                majority = new HashSet<>(bsSet);
            } else if (hsSize == maxSize && hsSet != null) {
                majority = new HashSet<>(hsSet);
            } else if (backupSize == maxSize) {
                majority = new HashSet<>(backupSet);
            } else if (localSize == maxSize) {
                majority = new HashSet<>(localImmortalSetBackup);
            } else if (hiddenSize == maxSize && hiddenSet != null) {
                majority = new HashSet<>(hiddenSet);
            } else {
                majority = new HashSet<>(mainSet);
            }

            Set<UUID> killSet = CombatRegistry.getKillSet();
            Set<UUID> deadConfirmed = CombatRegistry.getDeadConfirmedSet();
            for (UUID uuid : majority) {
                if (killSet.contains(uuid) || deadConfirmed.contains(uuid)) continue;
                if (!mainSet.contains(uuid)) mainSet.add(uuid);
                if (!backupSet.contains(uuid)) backupSet.add(uuid);
                localImmortalSetBackup.add(uuid);
                if (hiddenSet != null) hiddenSet.add(uuid);
            }

            CombatRegistry.syncImmortalSetFromBackup();
        } catch (Throwable ignored) {}
    }

    private static void resetGlobalDisableFlags() {
        if (!flagTargetsScanned) {
            flagTargetsScanned = true;
            scanForGlobalFlags();
        }
        for (ResetTarget target : globalFlagTargets) {
            try {
                boolean val = target.field.getBoolean(null);
                if (val) {
                    target.field.setBoolean(null, false);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void scanForGlobalFlags() {
        try {
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null) return;
            LALAgent.monitorClassScanning();
            Class<?>[] classes = inst.getAllLoadedClasses();
            for (Class<?> clazz : classes) {
                try {
                    String name = clazz.getName();
                    if (name.startsWith("java.") || name.startsWith("sun.")
                            || name.startsWith("jdk.") || name.startsWith("com.sun.")
                            || name.startsWith("org.objectweb.") || name.startsWith("net.minecraft.")
                            || name.startsWith("com.mojang.") || name.startsWith("jp.mikumiku.lal.")) {
                        continue;
                    }
                    for (Field f : clazz.getDeclaredFields()) {
                        try {
                            if (f.getType() == boolean.class
                                    && java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                    && java.lang.reflect.Modifier.isPublic(f.getModifiers())) {
                                f.setAccessible(true);
                                String fieldName = f.getName().toLowerCase();
                                if (fieldName.contains("return") || fieldName.contains("disable")
                                        || fieldName.contains("bypass") || fieldName.contains("block")
                                        || fieldName.contains("cancel") || fieldName.contains("stop")) {
                                    globalFlagTargets.add(new ResetTarget(clazz, f));
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void monitorHiddenClasses() {
        try {
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null) return;
            Class<?>[] allClasses = inst.getAllLoadedClasses();
            int hiddenCount = 0;
            for (Class<?> clazz : allClasses) {
                try {
                    String name = clazz.getName();
                    if (name.contains("/0x") && !name.startsWith("jp.mikumiku.lal.")) {
                        hiddenCount++;
                    }
                } catch (Throwable ignored) {}
            }
            if (lastHiddenClassCount >= 0 && hiddenCount > lastHiddenClassCount) {
                triggerRetransform();
            }
            lastHiddenClassCount = hiddenCount;
        } catch (Throwable ignored) {}
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

    private static Field threadLocalsField = null;
    private static boolean threadLocalsFieldResolved = false;
    private static Field threadLocalMapTableField = null;
    private static boolean threadLocalMapTableFieldResolved = false;
    private static Field threadLocalMapEntryValueField = null;
    private static boolean threadLocalMapEntryValueFieldResolved = false;

    private static void scanThreadLocals() {
        try {
            if (!threadLocalsFieldResolved) {
                threadLocalsFieldResolved = true;
                try {
                    threadLocalsField = Thread.class.getDeclaredField("threadLocals");
                    threadLocalsField.setAccessible(true);
                } catch (Throwable ignored) {}
            }
            if (threadLocalsField == null) return;

            Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();
            for (Thread thread : allThreads.keySet()) {
                try {
                    Object threadLocalMap = threadLocalsField.get(thread);
                    if (threadLocalMap == null) continue;

                    if (!threadLocalMapTableFieldResolved) {
                        threadLocalMapTableFieldResolved = true;
                        try {
                            threadLocalMapTableField = threadLocalMap.getClass().getDeclaredField("table");
                            threadLocalMapTableField.setAccessible(true);
                        } catch (Throwable ignored) {}
                    }
                    if (threadLocalMapTableField == null) continue;

                    Object table = threadLocalMapTableField.get(threadLocalMap);
                    if (table == null || !table.getClass().isArray()) continue;
                    Object[] entries = (Object[]) table;

                    for (Object entry : entries) {
                        if (entry == null) continue;
                        try {
                            if (!threadLocalMapEntryValueFieldResolved) {
                                threadLocalMapEntryValueFieldResolved = true;
                                try {
                                    threadLocalMapEntryValueField = entry.getClass().getDeclaredField("value");
                                    threadLocalMapEntryValueField.setAccessible(true);
                                } catch (Throwable ignored) {}
                            }
                            if (threadLocalMapEntryValueField == null) continue;

                            Object value = threadLocalMapEntryValueField.get(entry);
                            if (value == null) continue;

                            if (value instanceof LivingEntity) {
                                LivingEntity le = (LivingEntity) value;
                                if (CombatRegistry.isInKillSet(le.getUUID())) {
                                    try { ObjectKillEnforcer.neutralizeSingle(le); } catch (Throwable ignored) {}
                                }
                            } else if (value instanceof Entity) {
                                Entity ent = (Entity) value;
                                if (CombatRegistry.isInKillSet(ent.getUUID())) {
                                    try { ObjectKillEnforcer.neutralizeSingle(ent); } catch (Throwable ignored) {}
                                }
                            } else if (value instanceof java.util.Collection) {
                                java.util.Collection<?> col = (java.util.Collection<?>) value;
                                for (Object item : col.toArray()) {
                                    if (item instanceof LivingEntity) {
                                        LivingEntity le = (LivingEntity) item;
                                        if (CombatRegistry.isInKillSet(le.getUUID())) {
                                            try { ObjectKillEnforcer.neutralizeSingle(le); } catch (Throwable ignored) {}
                                        }
                                    } else if (item instanceof Entity) {
                                        Entity ent = (Entity) item;
                                        if (CombatRegistry.isInKillSet(ent.getUUID())) {
                                            try { ObjectKillEnforcer.neutralizeSingle(ent); } catch (Throwable ignored) {}
                                        }
                                    }
                                }
                            } else if (value instanceof java.util.Map) {
                                java.util.Collection<?> vals;
                                try { vals = ((java.util.Map<?,?>) value).values(); } catch (Throwable ignored) { continue; }
                                for (Object item : vals.toArray()) {
                                    if (item instanceof LivingEntity) {
                                        LivingEntity le = (LivingEntity) item;
                                        if (CombatRegistry.isInKillSet(le.getUUID())) {
                                            try { ObjectKillEnforcer.neutralizeSingle(le); } catch (Throwable ignored) {}
                                        }
                                    } else if (item instanceof Entity) {
                                        Entity ent = (Entity) item;
                                        if (CombatRegistry.isInKillSet(ent.getUUID())) {
                                            try { ObjectKillEnforcer.neutralizeSingle(ent); } catch (Throwable ignored) {}
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.lang.ref.WeakReference<Object>> deepScanCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static void deepScanAllClasses() {
        try {
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null) return;
            Class<?>[] allClasses = inst.getAllLoadedClasses();
            for (Class<?> clazz : allClasses) {
                try {
                    String name = clazz.getName();
                    if (name.startsWith("java.") || name.startsWith("javax.")
                            || name.startsWith("sun.") || name.startsWith("jdk.")
                            || name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")
                            || name.startsWith("cpw.mods.") || name.startsWith("net.minecraftforge.")
                            || name.startsWith("jp.mikumiku.lal.")) continue;

                    Field[] fields = FieldAccessUtil.safeGetDeclaredFields(clazz);
                    for (Field f : fields) {
                        try {
                            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            f.setAccessible(true);
                            Object val = f.get(null);
                            if (val == null) continue;

                            deepScanObject(val, 0, 2);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void deepScanObject(Object obj, int depth, int maxDepth) {
        if (obj == null || depth >= maxDepth) return;
        int id = System.identityHashCode(obj);
        java.lang.ref.WeakReference<Object> cached = deepScanCache.get(id);
        if (cached != null && cached.get() == obj) return;
        deepScanCache.put(id, new java.lang.ref.WeakReference<>(obj));

        if (obj instanceof LivingEntity) {
            LivingEntity le = (LivingEntity) obj;
            if (CombatRegistry.isInKillSet(le.getUUID())) {
                try { ObjectKillEnforcer.neutralizeSingle(le); } catch (Throwable ignored) {}
            }
            return;
        }
        if (obj instanceof Entity) {
            Entity ent = (Entity) obj;
            if (CombatRegistry.isInKillSet(ent.getUUID())) {
                try { ObjectKillEnforcer.neutralizeSingle(ent); } catch (Throwable ignored) {}
            }
            return;
        }

        String typeName = obj.getClass().getName();
        if (typeName.startsWith("java.") || typeName.startsWith("javax.")
                || typeName.startsWith("sun.") || typeName.startsWith("jdk.")
                || typeName.startsWith("net.minecraft.") || typeName.startsWith("com.mojang.")
                || typeName.startsWith("cpw.mods.") || typeName.startsWith("net.minecraftforge.")
                || typeName.startsWith("jp.mikumiku.lal.")) return;

        if (obj instanceof java.util.Collection) {
            try {
                for (Object item : ((java.util.Collection<?>) obj).toArray()) {
                    deepScanObject(item, depth + 1, maxDepth);
                }
            } catch (Throwable ignored) {}
            return;
        }
        if (obj instanceof java.util.Map) {
            try {
                for (Object item : ((java.util.Map<?,?>) obj).values().toArray()) {
                    deepScanObject(item, depth + 1, maxDepth);
                }
            } catch (Throwable ignored) {}
            return;
        }

        if (depth < maxDepth - 1) {
            for (Field f : FieldAccessUtil.safeGetDeclaredFields(obj.getClass())) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object fieldVal = f.get(obj);
                    deepScanObject(fieldVal, depth + 1, maxDepth);
                } catch (Throwable ignored) {}
            }
        }
    }

    public static void ensurePoolDaemonRunning() {
        if (poolDaemonRunning) {
            long lastBeat = poolDaemonHeartbeat.get();
            if (lastBeat > 0 && System.currentTimeMillis() - lastBeat < 5000) {
                return;
            }
        }
        poolDaemonRunning = true;
        try {
            ForkJoinPool.commonPool().execute(EnforcementDaemon::poolDaemonLoop);
        } catch (Throwable t) {
            try {
                new Thread(EnforcementDaemon::poolDaemonLoop).start();
            } catch (Throwable ignored) {}
        }
    }

    private static void poolDaemonLoop() {
        while (running && poolDaemonRunning) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.interrupted();
                continue;
            }
            try {
                poolDaemonHeartbeat.set(System.currentTimeMillis());
                long mainBeat = mainDaemonHeartbeat.get();
                if (mainBeat > 0 && System.currentTimeMillis() - mainBeat > 3000) {
                    ensureRunning();
                }
                try { DaemonWatchdog.start(); } catch (Throwable ignored) {}
                for (Map.Entry<UUID, WeakReference<LivingEntity>> entry : trackedEntities.entrySet()) {
                    try {
                        LivingEntity entity = entry.getValue().get();
                        if (entity == null) continue;
                        UUID uuid = entry.getKey();
                        if (CombatRegistry.isInImmortalSet(uuid)) {
                            enforceImmortal(entity);
                        } else if (CombatRegistry.isInKillSet(uuid)) {
                            enforceKill(entity);
                        }
                    } catch (Throwable ignored) {}
                }
                try {
                    CombatRegistry.cleanupDeadObjectRefs();
                } catch (Throwable ignored) {}
            } catch (ThreadDeath td) {
                continue;
            } catch (Throwable ignored) {}
        }
        poolDaemonRunning = false;
    }
}
