package jp.mikumiku.lal.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public class FieldAccessUtil {

    public static final VarHandle HEALTH;
    public static final VarHandle DEAD;
    public static final VarHandle DEATH_TIME;
    public static final VarHandle HURT_TIME;
    public static final VarHandle REMOVAL_REASON;

    static {
        VarHandle h = null, d = null, dt = null, ht = null, rr = null;
        try {
            MethodHandles.Lookup livingLookup = MethodHandles.privateLookupIn(LivingEntity.class, MethodHandles.lookup());
            h = findVarHandle(livingLookup, LivingEntity.class, float.class, "f_20958_", "health");
            dt = findVarHandle(livingLookup, LivingEntity.class, int.class, "f_20962_", "deathTime");
            d = findVarHandle(livingLookup, LivingEntity.class, boolean.class, "f_20960_", "dead");
            ht = findVarHandle(livingLookup, LivingEntity.class, int.class, "f_20955_", "hurtTime");
            MethodHandles.Lookup entityLookup = MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup());
            rr = findVarHandle(entityLookup, Entity.class, Entity.RemovalReason.class, "f_146801_", "removalReason");
        } catch (Throwable e) {
        }
        HEALTH = h;
        DEAD = d;
        DEATH_TIME = dt;
        HURT_TIME = ht;
        REMOVAL_REASON = rr;
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
        try {
            return clazz.getDeclaredFields();
        } catch (Throwable t) {
            return new Field[0];
        }
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
}
