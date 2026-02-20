package jp.mikumiku.lal.enforcement;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import jp.mikumiku.lal.core.BreakRegistry;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.util.FieldAccessUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public class BreakEnforcer {

    private static final ConcurrentHashMap<Class<?>, List<FieldTarget>> FIELD_CACHE = new ConcurrentHashMap<>();

    private static Field ITEMS_BY_ID_FIELD;
    private static boolean itemsByIdResolved = false;

    private static EntityDataAccessor<Float> DATA_HEALTH_ID_ACCESSOR;
    private static boolean healthIdResolved = false;

    public static class FieldTarget {
        public final Field field;
        public final FieldType type;

        public FieldTarget(Field field, FieldType type) {
            this.field = field;
            this.type = type;
        }
    }

    public enum FieldType {
        FLOAT_HEALTH,
        WEAK_HASH_MAP,
        HASH_MAP_ENTITY
    }

    public static void enforceAll() {
        for (UUID uuid : BreakRegistry.getBreakingUuids()) {
            try {
                BreakRegistry.BreakState state = BreakRegistry.getState(uuid);
                if (state == null) continue;
            } catch (Throwable ignored) {}
        }
    }

    public static void enforce(LivingEntity entity) {
        if (entity == null) return;
        try {
            UUID uuid = entity.getUUID();
            BreakRegistry.BreakState state = BreakRegistry.getState(uuid);
            if (state == null) return;

            float healthCap = state.healthCap;

            if (healthCap <= 0.0f) {
                if (!CombatRegistry.isInKillSet(uuid) && !CombatRegistry.isDeadConfirmed(uuid)) {
                    CombatRegistry.addToKillSet(uuid);
                    CombatRegistry.setForcedHealth(uuid, 0.0f);
                }
                state.finalized = true;
                return;
            }

            float trueHealth = readTrueHealth(entity);

            if (trueHealth > healthCap) {
                writeTrueHealth(entity, healthCap);
            }

            writeSynchedHealth(entity, healthCap);

            enforceIndependentHealthFields(entity, healthCap);

        } catch (Throwable ignored) {}
    }

    public static void immediateEnforce(LivingEntity entity, float healthCap) {
        if (entity == null) return;
        try {
            writeTrueHealth(entity, healthCap);
            writeSynchedHealth(entity, healthCap);
            enforceIndependentHealthFields(entity, healthCap);

            if (healthCap <= 0.0f) {
                if (FieldAccessUtil.DEAD != null) {
                    FieldAccessUtil.DEAD.set(entity, true);
                }
                if (FieldAccessUtil.DEATH_TIME != null) {
                    FieldAccessUtil.DEATH_TIME.set(entity, 19);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static float readTrueHealth(LivingEntity entity) {
        if (FieldAccessUtil.HEALTH != null) {
            try {
                return (float) FieldAccessUtil.HEALTH.get(entity);
            } catch (Throwable ignored) {}
        }
        return entity.getHealth();
    }

    private static void writeTrueHealth(LivingEntity entity, float value) {
        if (FieldAccessUtil.HEALTH != null) {
            try {
                FieldAccessUtil.HEALTH.set(entity, value);
            } catch (Throwable ignored) {}
        }
    }

    private static void writeSynchedHealth(LivingEntity entity, float value) {
        resolveHealthAccessor();
        if (DATA_HEALTH_ID_ACCESSOR != null) {
            try {
                entity.getEntityData().set(DATA_HEALTH_ID_ACCESSOR, Float.valueOf(value));
            } catch (Throwable ignored) {}
        }
        try {
            entity.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(value));
        } catch (Throwable ignored) {}
    }

    private static void enforceIndependentHealthFields(LivingEntity entity, float healthCap) {
        try {
            Class<?> clazz = entity.getClass();
            List<FieldTarget> targets = FIELD_CACHE.get(clazz);

            if (targets == null) {
                targets = scanForHealthFields(clazz);
                FIELD_CACHE.put(clazz, targets);
            }

            for (FieldTarget target : targets) {
                try {
                    switch (target.type) {
                        case FLOAT_HEALTH:
                            float currentVal = target.field.getFloat(entity);
                            if (currentVal > healthCap) {
                                target.field.setFloat(entity, healthCap);
                            }
                            break;
                        case WEAK_HASH_MAP:
                        case HASH_MAP_ENTITY:
                            enforceMapField(entity, target.field, healthCap);
                            break;
                    }
                } catch (Throwable ignored) {}
            }

            enforceSynchedEntityDataFloats(entity, healthCap);

        } catch (Throwable ignored) {}
    }

    private static List<FieldTarget> scanForHealthFields(Class<?> clazz) {
        List<FieldTarget> targets = new ArrayList<>();
        try {
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                String className = c.getName();
                if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) {
                    continue;
                }

                for (Field f : FieldAccessUtil.safeGetDeclaredFields(c)) {
                    try {
                        if (Modifier.isStatic(f.getModifiers())) continue;

                        String name = f.getName().toLowerCase();

                        if (f.getType() == float.class || f.getType() == Float.class) {
                            if (name.contains("health") || name.contains("hp") || name.contains("life")
                                    || name.contains("healts") || name.contains("bucket")
                                    || name.contains("lastHealth") || name.contains("lasthealth")) {
                                f.setAccessible(true);
                                targets.add(new FieldTarget(f, FieldType.FLOAT_HEALTH));
                            }
                        }

                        if (WeakHashMap.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            targets.add(new FieldTarget(f, FieldType.WEAK_HASH_MAP));
                        }

                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return targets;
    }

    @SuppressWarnings("unchecked")
    private static void enforceMapField(LivingEntity entity, Field field, float healthCap) {
        try {
            Object mapObj = field.get(entity);
            if (!(mapObj instanceof Map)) return;
            Map<Object, Object> map = (Map<Object, Object>) mapObj;

            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                if (entry.getValue() instanceof Float) {
                    float val = (Float) entry.getValue();
                    if (val > healthCap) {
                        entry.setValue(healthCap);
                    }
                } else if (entry.getValue() instanceof Double) {
                    double val = (Double) entry.getValue();
                    if (val > healthCap) {
                        entry.setValue((double) healthCap);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void enforceSynchedEntityDataFloats(LivingEntity entity, float healthCap) {
        try {
            resolveItemsByIdField();
            if (ITEMS_BY_ID_FIELD == null) return;

            Object itemsById = ITEMS_BY_ID_FIELD.get(entity.getEntityData());
            if (itemsById == null) return;

            Iterable<?> items = null;
            if (itemsById.getClass().isArray()) {
                items = java.util.Arrays.asList((Object[]) itemsById);
            } else {
                try {
                    java.lang.reflect.Method valuesMethod = itemsById.getClass().getMethod("values");
                    Object values = valuesMethod.invoke(itemsById);
                    if (values instanceof Iterable) items = (Iterable<?>) values;
                } catch (Throwable ignored) {}
            }
            if (items == null) return;

            resolveHealthAccessor();

            for (Object item : items) {
                if (item == null) continue;
                try {
                    Field accessorField = findDataItemField(item.getClass(), "accessor", "EntityDataAccessor");
                    Field valueField = findDataItemField(item.getClass(), "value", "Object");
                    if (accessorField == null || valueField == null) continue;

                    Object accessor = accessorField.get(item);
                    Object value = valueField.get(item);

                    if (DATA_HEALTH_ID_ACCESSOR != null && accessor.equals(DATA_HEALTH_ID_ACCESSOR)) {
                        continue;
                    }

                    if (value instanceof Float) {
                        float fVal = (Float) value;
                        if (fVal > healthCap && fVal > 0.0f) {
                            EntityDataAccessor<Float> typedAccessor = (EntityDataAccessor<Float>) accessor;
                            entity.getEntityData().set(typedAccessor, healthCap);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void resolveItemsByIdField() {
        if (itemsByIdResolved) return;
        itemsByIdResolved = true;
        for (String name : new String[]{"f_135354_", "itemsById"}) {
            try {
                ITEMS_BY_ID_FIELD = SynchedEntityData.class.getDeclaredField(name);
                ITEMS_BY_ID_FIELD.setAccessible(true);
                return;
            } catch (NoSuchFieldException ignored) {}
        }
        for (Field f : FieldAccessUtil.safeGetDeclaredFields(SynchedEntityData.class)) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> ft = f.getType();
            if (ft.isArray() && ft.getComponentType().getSimpleName().contains("DataItem")) {
                f.setAccessible(true);
                ITEMS_BY_ID_FIELD = f;
                return;
            }
            if (ft.getName().contains("Int2Object")) {
                f.setAccessible(true);
                ITEMS_BY_ID_FIELD = f;
                return;
            }
        }
    }

    private static void resolveHealthAccessor() {
        if (healthIdResolved) return;
        healthIdResolved = true;
        try {
            for (String name : new String[]{"f_20961_", "DATA_HEALTH_ID"}) {
                try {
                    Field f = LivingEntity.class.getDeclaredField(name);
                    f.setAccessible(true);
                    DATA_HEALTH_ID_ACCESSOR = (EntityDataAccessor<Float>) f.get(null);
                    return;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static final ConcurrentHashMap<String, Field> DATA_ITEM_FIELD_CACHE = new ConcurrentHashMap<>();

    private static Field findDataItemField(Class<?> clazz, String preferredName, String typeHint) {
        String cacheKey = clazz.getName() + ":" + preferredName;
        Field cached = DATA_ITEM_FIELD_CACHE.get(cacheKey);
        if (cached != null) return cached;

        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(preferredName);
                f.setAccessible(true);
                DATA_ITEM_FIELD_CACHE.put(cacheKey, f);
                return f;
            } catch (Throwable ignored) {}
            for (Field f : FieldAccessUtil.safeGetDeclaredFields(c)) {
                if (f.getType().getSimpleName().contains(typeHint)) {
                    try {
                        f.setAccessible(true);
                        DATA_ITEM_FIELD_CACHE.put(cacheKey, f);
                        return f;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }
}
