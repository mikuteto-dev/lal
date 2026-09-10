package jp.mikumiku.lal.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public class FieldAccessUtil {

    public static final VarHandle HEALTH;
    public static final VarHandle DEAD;
    public static final VarHandle DEATH_TIME;
    public static final VarHandle HURT_TIME;
    public static final VarHandle REMOVAL_REASON;
    public static final VarHandle ENTITY_ID;
    public static final VarHandle ENTITY_UUID;

    private static Object unsafeInstance;
    private static Method unsafePutFloat;
    private static Method unsafePutBoolean;
    private static Method unsafePutInt;
    private static Method unsafePutObject;
    private static Method unsafeGetFloat;
    private static Method unsafeGetBoolean;
    private static Method unsafeGetInt;
    private static Method unsafeGetObject;
    private static Method unsafeObjectFieldOffset;
    private static Method unsafeStaticFieldOffset;
    private static final ConcurrentHashMap<String, Long> fieldOffsetCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field[]> DECLARED_FIELDS_CACHE = new ConcurrentHashMap<>();
    private static final Field[] EMPTY_FIELDS = new Field[0];

    private static long entityIdOffset = -1;
    private static long entityUuidOffset = -1;

    static {
        VarHandle h = null, d = null, dt = null, ht = null, rr = null;
        VarHandle eid = null, euuid = null;
        try {
            MethodHandles.Lookup livingLookup = MethodHandles.privateLookupIn(LivingEntity.class, MethodHandles.lookup());
            h = findVarHandle(livingLookup, LivingEntity.class, float.class, "f_20769_", "health");
            dt = findVarHandle(livingLookup, LivingEntity.class, int.class, "f_20919_", "deathTime");
            d = findVarHandle(livingLookup, LivingEntity.class, boolean.class, "f_20890_", "dead");
            ht = findVarHandle(livingLookup, LivingEntity.class, int.class, "f_20916_", "hurtTime");
            MethodHandles.Lookup entityLookup = MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup());
            rr = findVarHandle(entityLookup, Entity.class, Entity.RemovalReason.class, "f_146795_", "removalReason");
            eid = findVarHandle(entityLookup, Entity.class, int.class, "f_19848_", "id");
            euuid = findVarHandle(entityLookup, Entity.class, UUID.class, "f_19820_", "uuid");
        } catch (Throwable e) {
        }
        HEALTH = h;
        DEAD = d;
        DEATH_TIME = dt;
        HURT_TIME = ht;
        REMOVAL_REASON = rr;
        ENTITY_ID = eid;
        ENTITY_UUID = euuid;

        try {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafeInstance = theUnsafe.get(null);
            Class<?> unsafeClass = unsafeInstance.getClass();
            unsafePutFloat = unsafeClass.getMethod("putFloat", Object.class, long.class, float.class);
            unsafePutBoolean = unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class);
            unsafePutInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
            unsafePutObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            unsafeGetFloat = unsafeClass.getMethod("getFloat", Object.class, long.class);
            unsafeGetBoolean = unsafeClass.getMethod("getBoolean", Object.class, long.class);
            unsafeGetInt = unsafeClass.getMethod("getInt", Object.class, long.class);
            unsafeGetObject = unsafeClass.getMethod("getObject", Object.class, long.class);
            unsafeObjectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            unsafeStaticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
        } catch (Throwable ignored) {}
    }

    public static VarHandle findVarHandle(MethodHandles.Lookup lookup, Class<?> clazz, Class<?> type, String... names) {
        for (String name : names) {
            try {
                return lookup.findVarHandle(clazz, name, type);
            } catch (Throwable t) {
            }
        }
        return null;
    }

    public static Field[] safeGetDeclaredFields(Class<?> clazz) {
        return DECLARED_FIELDS_CACHE.computeIfAbsent(clazz, c -> {
            try {
                return c.getDeclaredFields();
            } catch (Throwable t) {
                return EMPTY_FIELDS;
            }
        });
    }

    public static Field findAccessibleField(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable t) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    public static Method findAccessibleMethod(Class<?> clazz, String name, Class<?>... params) {
        while (clazz != null && clazz != Object.class) {
            try {
                Method m = clazz.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable t) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    public static boolean isUnsafeAvailable() {
        return unsafeInstance != null;
    }

    public static Object getUnsafe() {
        return unsafeInstance;
    }

    private static long getFieldOffset(Field field) {
        String key = field.getDeclaringClass().getName() + "#" + field.getName();
        Long cached = fieldOffsetCache.get(key);
        if (cached != null) return cached;
        try {
            long offset;
            if (Modifier.isStatic(field.getModifiers())) {
                offset = (long) unsafeStaticFieldOffset.invoke(unsafeInstance, field);
            } else {
                offset = (long) unsafeObjectFieldOffset.invoke(unsafeInstance, field);
            }
            fieldOffsetCache.put(key, offset);
            return offset;
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static void unsafePutFloat(Object obj, Field field, float value) {
        try {
            long offset = getFieldOffset(field);
            if (offset >= 0) unsafePutFloat.invoke(unsafeInstance, obj, offset, value);
        } catch (Throwable ignored) {}
    }

    public static void unsafePutBoolean(Object obj, Field field, boolean value) {
        try {
            long offset = getFieldOffset(field);
            if (offset >= 0) unsafePutBoolean.invoke(unsafeInstance, obj, offset, value);
        } catch (Throwable ignored) {}
    }

    public static void unsafePutInt(Object obj, Field field, int value) {
        try {
            long offset = getFieldOffset(field);
            if (offset >= 0) unsafePutInt.invoke(unsafeInstance, obj, offset, value);
        } catch (Throwable ignored) {}
    }

    public static void unsafePutObject(Object obj, Field field, Object value) {
        try {
            long offset = getFieldOffset(field);
            if (offset >= 0) unsafePutObject.invoke(unsafeInstance, obj, offset, value);
        } catch (Throwable ignored) {}
    }

    public static float unsafeGetFloat(Object obj, Field field) {
        try {
            long offset = getFieldOffset(field);
            if (offset >= 0) return (float) unsafeGetFloat.invoke(unsafeInstance, obj, offset);
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    public static boolean unsafeGetBoolean(Object obj, Field field) {
        try {
            long offset = getFieldOffset(field);
            if (offset >= 0) return (boolean) unsafeGetBoolean.invoke(unsafeInstance, obj, offset);
        } catch (Throwable ignored) {}
        return false;
    }

    public static int unsafeGetInt(Object obj, Field field) {
        try {
            long offset = getFieldOffset(field);
            if (offset >= 0) return (int) unsafeGetInt.invoke(unsafeInstance, obj, offset);
        } catch (Throwable ignored) {}
        return 0;
    }

    public static void unsafePutFloatDirect(Object obj, long offset, float value) {
        try {
            if (offset >= 0) unsafePutFloat.invoke(unsafeInstance, obj, offset, value);
        } catch (Throwable ignored) {}
    }

    public static void unsafePutBooleanDirect(Object obj, long offset, boolean value) {
        try {
            if (offset >= 0) unsafePutBoolean.invoke(unsafeInstance, obj, offset, value);
        } catch (Throwable ignored) {}
    }

    public static void unsafePutIntDirect(Object obj, long offset, int value) {
        try {
            if (offset >= 0) unsafePutInt.invoke(unsafeInstance, obj, offset, value);
        } catch (Throwable ignored) {}
    }

    public static void unsafePutObjectDirect(Object obj, long offset, Object value) {
        try {
            if (offset >= 0) unsafePutObject.invoke(unsafeInstance, obj, offset, value);
        } catch (Throwable ignored) {}
    }

    public static void unsafeSetStaticBoolean(Field field, boolean value) {
        try {
            if (unsafeInstance == null) return;
            Class<?> unsafeClass = unsafeInstance.getClass();
            long offset = (long) unsafeStaticFieldOffset.invoke(unsafeInstance, field);
            Method putBoolean = unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class);
            Method staticBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            Object base = staticBase.invoke(unsafeInstance, field);
            putBoolean.invoke(unsafeInstance, base, offset, value);
        } catch (Throwable ignored) {}
    }

    public static long resolveFieldOffset(Class<?> clazz, String... names) {
        for (String name : names) {
            String key = clazz.getName() + "#" + name;
            Long cached = fieldOffsetCache.get(key);
            if (cached != null) return cached;
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                long offset = getFieldOffset(f);
                if (offset >= 0) return offset;
            } catch (Throwable ignored) {}
        }
        return -1L;
    }

    private static volatile boolean varHandleCompromised = false;
    private static volatile int lastVerifyTick = 0;
    private static final int VERIFY_INTERVAL = 100;
    private static long healthFieldOffset = -1;
    private static long deadFieldOffset = -1;

    public static boolean isVarHandleCompromised() {
        return varHandleCompromised;
    }

    public static void verifyVarHandleIntegrity(LivingEntity entity, int currentTick) {
        if (currentTick - lastVerifyTick < VERIFY_INTERVAL) return;
        lastVerifyTick = currentTick;
        if (unsafeInstance == null || HEALTH == null) return;
        try {
            if (healthFieldOffset < 0) {
                Field hf = null;
                for (String name : new String[]{"f_20769_", "health"}) {
                    try {
                        hf = LivingEntity.class.getDeclaredField(name);
                        hf.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (hf != null) {
                    healthFieldOffset = (long) unsafeObjectFieldOffset.invoke(unsafeInstance, hf);
                }
            }
            if (healthFieldOffset < 0) return;
            float vhValue = (float) HEALTH.get(entity);
            float unsafeValue = (float) unsafeGetFloat.invoke(unsafeInstance, entity, healthFieldOffset);
            if (Float.compare(vhValue, unsafeValue) != 0) {
                varHandleCompromised = true;
            } else {
                varHandleCompromised = false;
            }
        } catch (Throwable ignored) {
            varHandleCompromised = true;
        }
    }

    public static void verifyUnsafeIntegrity() {
        if (unsafeInstance == null) return;
        try {
            String className = unsafeInstance.getClass().getName();
            if (!className.equals("sun.misc.Unsafe")) {
                reinitializeUnsafe();
            }
        } catch (Throwable ignored) {}
    }

    private static void reinitializeUnsafe() {
        try {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object fresh = theUnsafe.get(null);
            if (fresh != null && fresh.getClass().getName().equals("sun.misc.Unsafe")) {
                applyUnsafeInstance(fresh);
                return;
            }
        } catch (Throwable ignored) {}
        reinitializeUnsafeViaReflectionFactory();
    }

    private static void reinitializeUnsafeViaReflectionFactory() {
        try {
            Class<?> rfClass = Class.forName("sun.reflect.ReflectionFactory");
            Method getRF = rfClass.getDeclaredMethod("getReflectionFactory");
            Object rf = getRF.invoke(null);
            Constructor<MethodHandles.Lookup> origCtor =
                    MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            Method newSerCtor = rfClass.getDeclaredMethod("newConstructorForSerialization",
                    Class.class, Constructor.class);
            @SuppressWarnings("unchecked")
            Constructor<MethodHandles.Lookup> serCtor =
                    (Constructor<MethodHandles.Lookup>) newSerCtor.invoke(rf, MethodHandles.Lookup.class, origCtor);
            MethodHandles.Lookup fullLookup = serCtor.newInstance(Object.class, 0xF);
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            VarHandle vh = fullLookup.findStaticVarHandle(unsafeClass, "theUnsafe", unsafeClass);
            Object fresh = vh.get();
            if (fresh != null && fresh.getClass().getName().equals("sun.misc.Unsafe")) {
                applyUnsafeInstance(fresh);
            }
        } catch (Throwable ignored) {}
    }

    private static void applyUnsafeInstance(Object fresh) {
        try {
            unsafeInstance = fresh;
            Class<?> unsafeClass = fresh.getClass();
            unsafePutFloat = unsafeClass.getMethod("putFloat", Object.class, long.class, float.class);
            unsafePutBoolean = unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class);
            unsafePutInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
            unsafePutObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            unsafeGetFloat = unsafeClass.getMethod("getFloat", Object.class, long.class);
            unsafeGetBoolean = unsafeClass.getMethod("getBoolean", Object.class, long.class);
            unsafeGetInt = unsafeClass.getMethod("getInt", Object.class, long.class);
            unsafeGetObject = unsafeClass.getMethod("getObject", Object.class, long.class);
            unsafeObjectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            unsafeStaticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
        } catch (Throwable ignored) {}
    }

    public static int getEntityIdDirect(Entity entity) {
        try {
            if (ENTITY_ID != null) {
                return (int) ENTITY_ID.get(entity);
            }
        } catch (Throwable ignored) {}
        try {
            if (unsafeInstance != null) {
                if (entityIdOffset < 0) {
                    for (String name : new String[]{"f_19848_", "id"}) {
                        try {
                            Field f = Entity.class.getDeclaredField(name);
                            f.setAccessible(true);
                            entityIdOffset = (long) unsafeObjectFieldOffset.invoke(unsafeInstance, f);
                            break;
                        } catch (Throwable ignored2) {}
                    }
                }
                if (entityIdOffset >= 0) {
                    return (int) unsafeGetInt.invoke(unsafeInstance, entity, entityIdOffset);
                }
            }
        } catch (Throwable ignored) {}
        try {
            for (String name : new String[]{"f_19848_", "id"}) {
                try {
                    Field f = Entity.class.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.getInt(entity);
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}
        try {
            return entity.getId();
        } catch (Throwable ignored) {}
        return -1;
    }

    public static UUID getEntityUuidDirect(Entity entity) {
        try {
            if (ENTITY_UUID != null) {
                return (UUID) ENTITY_UUID.get(entity);
            }
        } catch (Throwable ignored) {}
        try {
            if (unsafeInstance != null) {
                if (entityUuidOffset < 0) {
                    for (String name : new String[]{"f_19820_", "uuid"}) {
                        try {
                            Field f = Entity.class.getDeclaredField(name);
                            f.setAccessible(true);
                            entityUuidOffset = (long) unsafeObjectFieldOffset.invoke(unsafeInstance, f);
                            break;
                        } catch (Throwable ignored2) {}
                    }
                }
                if (entityUuidOffset >= 0) {
                    Object val = unsafeGetObject.invoke(unsafeInstance, entity, entityUuidOffset);
                    if (val instanceof UUID) return (UUID) val;
                }
            }
        } catch (Throwable ignored) {}
        try {
            for (String name : new String[]{"f_19820_", "uuid"}) {
                try {
                    Field f = Entity.class.getDeclaredField(name);
                    f.setAccessible(true);
                    Object val = f.get(entity);
                    if (val instanceof UUID) return (UUID) val;
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}
        try {
            return entity.getUUID();
        } catch (Throwable ignored) {}
        return null;
    }
}
