package jp.mikumiku.lal.transformer;

import jp.mikumiku.lal.core.CombatRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public class EntityMethodHooks {
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    public EntityMethodHooks() {
        super();
    }

    public static void setBypass(boolean bypass) {
        BYPASS.set(bypass);
    }

    public static boolean isBypass() {
        return BYPASS.get();
    }

    public static boolean shouldReplaceMethod(Object obj) {
        if (BYPASS.get().booleanValue()) {
            return false;
        }
        if (!(obj instanceof Entity)) {
            return false;
        }
        Entity entity = (Entity)obj;
        try {
            return CombatRegistry.isInKillSet(entity) || CombatRegistry.isInImmortalSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID());
        }
        catch (Exception e) {
            return false;
        }
    }

    public static float replaceGetHealth(Object obj) {
        if (!(obj instanceof LivingEntity)) {
            return 20.0f;
        }
        LivingEntity entity = (LivingEntity)obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) {
                return EntityMethodHooks.sanitizeHealth(forced.floatValue(), entity);
            }
            if (CombatRegistry.isInImmortalSet((Entity)entity)) {
                return Math.max(entity.getMaxHealth(), 1.0f);
            }
            if (CombatRegistry.isInKillSet((Entity)entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return 0.0f;
            }
        }
        catch (Exception exception) {
        }
        return entity.getMaxHealth();
    }

    public static boolean replaceIsDeadOrDying(Object obj) {
        if (!(obj instanceof Entity)) {
            return false;
        }
        Entity entity = (Entity)obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) {
                return forced.floatValue() <= 0.0f;
            }
            if (CombatRegistry.isInImmortalSet(entity)) {
                return false;
            }
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return true;
            }
        }
        catch (Exception exception) {
        }
        return false;
    }

    public static boolean replaceIsAlive(Object obj) {
        if (!(obj instanceof Entity)) {
            return true;
        }
        Entity entity = (Entity)obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) {
                return forced.floatValue() > 0.0f;
            }
            if (CombatRegistry.isInImmortalSet(entity)) {
                return true;
            }
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return false;
            }
        }
        catch (Exception exception) {
        }
        return true;
    }

    public static float getHealth(Object obj, float original) {
        if (BYPASS.get().booleanValue()) {
            return original;
        }
        if (!(obj instanceof LivingEntity)) {
            return original;
        }
        LivingEntity entity = (LivingEntity)obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) {
                return EntityMethodHooks.sanitizeHealth(forced.floatValue(), entity);
            }
            if (CombatRegistry.isInImmortalSet((Entity)entity)) {
                float result = Math.max(entity.getMaxHealth(), 1.0f);
                if (Float.isNaN(result) || Float.isInfinite(result) || result <= 0.0f) {
                    result = 20.0f;
                }
                return result;
            }
            if (CombatRegistry.isInKillSet((Entity)entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return 0.0f;
            }
        }
        catch (Exception exception) {
        }
        if (Float.isNaN(original) || Float.isInfinite(original)) {
            return 20.0f;
        }
        return original;
    }

    public static float getHealth(float original, Object obj) {
        return EntityMethodHooks.getHealth(obj, original);
    }

    public static boolean isDeadOrDying(Object obj, boolean original) {
        if (BYPASS.get().booleanValue()) {
            return original;
        }
        if (!(obj instanceof Entity)) {
            return original;
        }
        Entity entity = (Entity)obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) {
                return forced.floatValue() <= 0.0f;
            }
            if (CombatRegistry.isInImmortalSet(entity)) {
                return false;
            }
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return true;
            }
        }
        catch (Exception exception) {
        }
        return original;
    }

    public static boolean isDeadOrDying(boolean original, Object obj) {
        return EntityMethodHooks.isDeadOrDying(obj, original);
    }

    public static boolean isAlive(Object obj, boolean original) {
        if (BYPASS.get().booleanValue()) {
            return original;
        }
        if (!(obj instanceof Entity)) {
            return original;
        }
        Entity entity = (Entity)obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null) {
                return forced.floatValue() > 0.0f;
            }
            if (CombatRegistry.isInImmortalSet(entity)) {
                return true;
            }
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return false;
            }
        }
        catch (Exception exception) {
        }
        return original;
    }

    public static boolean isAlive(boolean original, Object obj) {
        return EntityMethodHooks.isAlive(obj, original);
    }

    public static boolean isRemoved(Object obj, boolean original) {
        if (BYPASS.get().booleanValue()) {
            return original;
        }
        if (!(obj instanceof Entity)) {
            return original;
        }
        Entity entity = (Entity)obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null && forced.floatValue() <= 0.0f) {
                return true;
            }
            if (CombatRegistry.isInImmortalSet(entity)) {
                return false;
            }
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return true;
            }
        }
        catch (Exception exception) {
        }
        return original;
    }

    public static boolean isRemoved(boolean original, Object obj) {
        return EntityMethodHooks.isRemoved(obj, original);
    }

    public static Entity.RemovalReason getRemovalReason(Object obj, Entity.RemovalReason original) {
        if (BYPASS.get().booleanValue()) {
            return original;
        }
        if (!(obj instanceof Entity)) {
            return original;
        }
        Entity entity = (Entity)obj;
        try {
            Float forced = CombatRegistry.getForcedHealth(entity.getUUID());
            if (forced != null && forced.floatValue() <= 0.0f) {
                return Entity.RemovalReason.KILLED;
            }
            if (CombatRegistry.isInImmortalSet(entity)) {
                return null;
            }
            if (CombatRegistry.isInKillSet(entity) || CombatRegistry.isDeadConfirmed(entity.getUUID())) {
                return Entity.RemovalReason.KILLED;
            }
        }
        catch (Exception exception) {
        }
        return original;
    }

    public static Entity.RemovalReason getRemovalReason(Entity.RemovalReason original, Object obj) {
        return EntityMethodHooks.getRemovalReason(obj, original);
    }

    private static float sanitizeHealth(float value, LivingEntity entity) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return Math.max(entity.getMaxHealth(), 20.0f);
        }
        return value;
    }
}

