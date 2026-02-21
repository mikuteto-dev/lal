package jp.mikumiku.lal.enforcement;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.util.FieldAccessUtil;
import jp.mikumiku.lal.core.EntityLedger;
import jp.mikumiku.lal.core.EntityLedgerEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class ImmortalEnforcer {
    private static VarHandle HEALTH_HANDLE;
    private static VarHandle DEATH_TIME_HANDLE;
    private static VarHandle REMOVAL_REASON_HANDLE;
    private static VarHandle DEAD_HANDLE;
    private static VarHandle HURT_TIME_HANDLE;
    private static Field HEALTH_FIELD;
    private static Field DEATH_TIME_FIELD;
    private static Field DEAD_FIELD;
    private static Field REMOVAL_REASON_FIELD;
    private static Field HURT_TIME_FIELD;
    private static Field VALID_FIELD;
    private static EntityDataAccessor<Float> DATA_HEALTH_ID_ACCESSOR;
    private static Field ENTITY_DATA_ITEMS_BY_ID;
    private static final Set<String> VANILLA_FLOAT_FIELDS;
    private static final Set<String> VANILLA_BOOLEAN_FIELDS;

    public ImmortalEnforcer() {
        super();
    }

    private static Field findReflectionField(Class<?> clazz, Class<?> fieldType, String ... names) {
        for (String string : names) {
            try {
                Field f = clazz.getDeclaredField(string);
                f.setAccessible(true);
                return f;
            }
            catch (Throwable f) {
            }
        }
        for (Field field : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != fieldType) continue;
            String n = field.getName().toLowerCase();
            if (fieldType == Float.TYPE && (n.contains("health") || n.equals("f_20958_"))) {
                try {
                    field.setAccessible(true);
                    return field;
                }
                catch (Throwable throwable) {
                    {}
                }
            }
            if (fieldType == Entity.RemovalReason.class) {
                try {
                    field.setAccessible(true);
                    return field;
                }
                catch (Throwable throwable) {
                    {}
                }
            }
            if (fieldType == Boolean.TYPE && (n.contains("dead") || n.equals("f_20960_"))) {
                try {
                    field.setAccessible(true);
                    return field;
                }
                catch (Throwable throwable) {
                    {}
                }
            }
            if (fieldType != Integer.TYPE) continue;
            if (n.contains("deathtime") || n.contains("death") || n.equals("f_20962_")) {
                try {
                    field.setAccessible(true);
                    return field;
                }
                catch (Throwable throwable) {
                    {}
                }
            }
            if (!n.contains("hurttime") && !n.contains("hurt") && !n.equals("f_20955_")) continue;
            try {
                field.setAccessible(true);
                return field;
            }
            catch (Throwable throwable) {
                {}
            }
        }
        return null;
    }

    private static void setHealthField(LivingEntity entity, float value) {
        if (HEALTH_HANDLE != null) {
            HEALTH_HANDLE.set(entity, value);
            return;
        }
        if (HEALTH_FIELD != null) {
            try {
                HEALTH_FIELD.setFloat(entity, value);
            }
            catch (Throwable throwable) {
                {}
            }
        }
    }

    private static float getHealthField(LivingEntity entity) {
        if (HEALTH_HANDLE != null) {
            return (float) HEALTH_HANDLE.get(entity);
        }
        if (HEALTH_FIELD != null) {
            try {
                return HEALTH_FIELD.getFloat(entity);
            }
            catch (Throwable throwable) {
                {}
            }
        }
        return entity.getHealth();
    }

    private static void setDeathTimeField(LivingEntity entity, int value) {
        if (DEATH_TIME_HANDLE != null) {
            DEATH_TIME_HANDLE.set(entity, value);
            return;
        }
        if (DEATH_TIME_FIELD != null) {
            try {
                DEATH_TIME_FIELD.setInt(entity, value);
            }
            catch (Throwable throwable) {
                {}
            }
        }
    }

    private static void setDeadField(LivingEntity entity, boolean value) {
        if (DEAD_HANDLE != null) {
            DEAD_HANDLE.set(entity, value);
            return;
        }
        if (DEAD_FIELD != null) {
            try {
                DEAD_FIELD.setBoolean(entity, value);
            }
            catch (Throwable throwable) {
                {}
            }
        }
    }

    private static boolean getDeadField(LivingEntity entity) {
        if (DEAD_HANDLE != null) {
            return (boolean) DEAD_HANDLE.get(entity);
        }
        if (DEAD_FIELD != null) {
            try {
                return DEAD_FIELD.getBoolean(entity);
            }
            catch (Throwable throwable) {
                {}
            }
        }
        return entity.isDeadOrDying();
    }

    private static void setHurtTimeField(LivingEntity entity, int value) {
        if (HURT_TIME_HANDLE != null) {
            HURT_TIME_HANDLE.set(entity, value);
            return;
        }
        if (HURT_TIME_FIELD != null) {
            try {
                HURT_TIME_FIELD.setInt(entity, value);
            }
            catch (Throwable throwable) {
                {}
            }
        }
    }

    private static void setRemovalReasonField(Entity entity, Entity.RemovalReason value) {
        if (REMOVAL_REASON_HANDLE != null) {
            REMOVAL_REASON_HANDLE.set(entity, value);
            return;
        }
        if (REMOVAL_REASON_FIELD != null) {
            try {
                REMOVAL_REASON_FIELD.set(entity, value);
            }
            catch (Throwable throwable) {
                {}
            }
        }
    }

    private static Entity.RemovalReason getRemovalReasonField(Entity entity) {
        if (REMOVAL_REASON_HANDLE != null) {
            return (Entity.RemovalReason) REMOVAL_REASON_HANDLE.get(entity);
        }
        if (REMOVAL_REASON_FIELD != null) {
            try {
                return (Entity.RemovalReason)REMOVAL_REASON_FIELD.get(entity);
            }
            catch (Throwable throwable) {
                {}
            }
        }
        return entity.getRemovalReason();
    }

    public static float getRawHealth(LivingEntity entity) {
        return ImmortalEnforcer.getHealthField(entity);
    }

    public static boolean getRawDead(LivingEntity entity) {
        return ImmortalEnforcer.getDeadField(entity);
    }

    public static int getRawDeathTime(LivingEntity entity) {
        if (DEATH_TIME_HANDLE != null) {
            return (int) DEATH_TIME_HANDLE.get(entity);
        }
        if (DEATH_TIME_FIELD != null) {
            try {
                return DEATH_TIME_FIELD.getInt(entity);
            }
            catch (Throwable throwable) {
                {}
            }
        }
        return entity.deathTime;
    }

    public static int getRawHurtTime(LivingEntity entity) {
        if (HURT_TIME_HANDLE != null) {
            return (int) HURT_TIME_HANDLE.get(entity);
        }
        if (HURT_TIME_FIELD != null) {
            try {
                return HURT_TIME_FIELD.getInt(entity);
            }
            catch (Throwable throwable) {
                {}
            }
        }
        return entity.hurtTime;
    }

    public static void setRawHealth(LivingEntity entity, float value) {
        ImmortalEnforcer.setHealthField(entity, value);
    }

    public static void setRawDead(LivingEntity entity, boolean value) {
        ImmortalEnforcer.setDeadField(entity, value);
    }

    public static void setRawDeathTime(LivingEntity entity, int value) {
        ImmortalEnforcer.setDeathTimeField(entity, value);
    }

    public static void setRawHurtTime(LivingEntity entity, int value) {
        ImmortalEnforcer.setHurtTimeField(entity, value);
    }

    public static void enforceImmortality(LivingEntity entity) {
        block86: {
            block85: {
                block83: {
                    block82: {
                        float maxHealth;
                        UUID uuid;
                        block81: {
                            uuid = entity.getUUID();
                            if (!CombatRegistry.isInImmortalSet(uuid)) {
                                return;
                            }
                            maxHealth = entity.getMaxHealth();
                            if (maxHealth <= 0.0f) {
                                maxHealth = 20.0f;
                            }
                            try {
                                AttributeInstance maxHealthAttr = entity.getAttribute(Attributes.MAX_HEALTH);
                                if (maxHealthAttr == null) break block81;
                                if (maxHealthAttr.getBaseValue() <= 0.0) {
                                    maxHealthAttr.setBaseValue(20.0);
                                }
                                try {
                                    for (AttributeModifier mod : maxHealthAttr.getModifiers().toArray(new AttributeModifier[0])) {
                                        if (!(mod.getAmount() < 0.0)) continue;
                                        maxHealthAttr.removeModifier(mod);
                                    }
                                }
                                catch (Throwable throwable) {
                                    {}
                                }
                                if ((maxHealth = entity.getMaxHealth()) <= 0.0f) {
                                    maxHealth = 20.0f;
                                }
                            }
                            catch (Throwable maxHealthAttr) {
                                {}
                            }
                        }
                        try {
                            EntityLedgerEntry entry = EntityLedger.get().getOrCreate(uuid);
                            float currentHealth = entity.getHealth();
                            if (currentHealth > 0.0f && currentHealth >= maxHealth) {
                                entry.lastKnownHealth = maxHealth;
                            } else if (currentHealth < entry.lastKnownHealth) {
                                maxHealth = Math.max(maxHealth, entry.lastKnownHealth);
                            }
                        }
                        catch (Throwable entry) {
                            {}
                        }
                        CombatRegistry.setForcedHealth(uuid, maxHealth);
                        entity.setHealth(maxHealth);
                        ImmortalEnforcer.setHealthField(entity, maxHealth);
                        if (DATA_HEALTH_ID_ACCESSOR != null) {
                            try {
                                entity.getEntityData().set(DATA_HEALTH_ID_ACCESSOR, Float.valueOf(maxHealth));
                            }
                            catch (Throwable entry) {
                            }
                        }
                        ImmortalEnforcer.setDeathTimeField(entity, 0);
                        ImmortalEnforcer.setDeadField(entity, false);
                        ImmortalEnforcer.setHurtTimeField(entity, 0);
                        entity.hurtTime = 0;
                        Entity.RemovalReason currentRemoval = ImmortalEnforcer.getRemovalReasonField((Entity)entity);
                        if (currentRemoval != null && entity.isAddedToWorld()) {
                            ImmortalEnforcer.setRemovalReasonField((Entity)entity, null);
                        }
                        try {
                            if (entity.getPose() == Pose.DYING) {
                                entity.setPose(Pose.STANDING);
                            }
                        }
                        catch (Throwable currentHealth) {
                            {}
                        }
                        try {
                            if (VALID_FIELD != null && !entity.isAddedToWorld()) {
                                VALID_FIELD.set(entity, true);
                            }
                        }
                        catch (Throwable currentHealth) {
                            {}
                        }
                        try {
                            AttributeInstance armorAttr = entity.getAttribute(Attributes.ARMOR);
                            if (armorAttr != null && armorAttr.getBaseValue() <= 0.0) {
                                boolean hasArmor = false;
                                for (ItemStack stack : entity.getArmorSlots()) {
                                    if (stack.isEmpty()) continue;
                                    hasArmor = true;
                                    break;
                                }
                                if (hasArmor) {
                                    armorAttr.setBaseValue(0.0);
                                }
                            }
                            if (armorAttr == null) break block82;
                            try {
                                for (AttributeModifier mod : armorAttr.getModifiers().toArray(new AttributeModifier[0])) {
                                    if (!(mod.getAmount() < -100.0)) continue;
                                    armorAttr.removeModifier(mod);
                                }
                            }
                            catch (Exception hasArmor) {
                            }
                        }
                        catch (Throwable armorAttr) {
                            {}
                        }
                    }
                    try {
                        AttributeInstance toughnessAttr = entity.getAttribute(Attributes.ARMOR_TOUGHNESS);
                        if (toughnessAttr == null) break block83;
                        try {
                            for (AttributeModifier mod : toughnessAttr.getModifiers().toArray(new AttributeModifier[0])) {
                                if (!(mod.getAmount() < -100.0)) continue;
                                toughnessAttr.removeModifier(mod);
                            }
                        }
                        catch (Exception hasArmor) {
                            {}
                        }
                    }
                    catch (Throwable toughnessAttr) {
                        {}
                    }
                }
                try {
                    entity.noPhysics = false;
                }
                catch (Throwable toughnessAttr) {
                }
                try {
                    entity.setNoGravity(false);
                }
                catch (Throwable toughnessAttr) {
                }
                entity.invulnerableTime = 0;
                try {
                    if (entity.getAbsorptionAmount() < 0.0f) {
                        entity.setAbsorptionAmount(0.0f);
                    }
                }
                catch (Throwable toughnessAttr) {
                }
                ImmortalEnforcer.resetSuspiciousEntityData(entity);
                ImmortalEnforcer.resetMixinInjectedBooleanFields(entity);
                ImmortalEnforcer.resetSuspiciousNBTData(entity);
                if (entity instanceof Player && LALSwordItem.hasLALEquipment((Player) entity)) {
                    ImmortalEnforcer.ensurePositiveIntegerData(entity);
                }
                try {
                    Class<MobEffects> effects = MobEffects.class;
                    entity.removeEffect(MobEffects.WITHER);
                    entity.removeEffect(MobEffects.POISON);
                    entity.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    entity.removeEffect(MobEffects.LEVITATION);
                    entity.removeEffect(MobEffects.BLINDNESS);
                    entity.removeEffect(MobEffects.DIG_SLOWDOWN);
                    entity.removeEffect(MobEffects.WEAKNESS);
                    entity.removeEffect(MobEffects.HUNGER);
                    entity.removeEffect(MobEffects.CONFUSION);
                    entity.removeEffect(MobEffects.DARKNESS);
                    try {
                        ArrayList<MobEffectInstance> activeEffects = new ArrayList<>(entity.getActiveEffects());
                        for (MobEffectInstance effect : activeEffects) {
                            try {
                                Map<Attribute, AttributeModifier> attributeModifiers = effect.getEffect().getAttributeModifiers();
                                boolean hasNegative = false;
                                for (Map.Entry<Attribute, AttributeModifier> entry : attributeModifiers.entrySet()) {
                                    if (!(((AttributeModifier)entry.getValue()).getAmount() < 0.0)) continue;
                                    hasNegative = true;
                                    break;
                                }
                                if (!hasNegative) continue;
                                entity.removeEffect(effect.getEffect());
                            }
                            catch (Exception attributeModifiers) {
                                {}
                            }
                        }
                    }
                    catch (Exception activeEffects) {
                        {}
                    }
                }
                catch (Throwable effects) {
                    {}
                }
                try {
                    ImmortalEnforcer.restoreAttribute(entity, Attributes.MOVEMENT_SPEED, 0.1);
                    ImmortalEnforcer.restoreAttribute(entity, Attributes.FLYING_SPEED, 0.4);
                    ImmortalEnforcer.restoreAttribute(entity, Attributes.ATTACK_DAMAGE, 1.0);
                    ImmortalEnforcer.restoreAttribute(entity, Attributes.ATTACK_SPEED, 4.0);
                    ImmortalEnforcer.restoreAttribute(entity, Attributes.LUCK, 0.0);
                    AttributeInstance kbAttr = entity.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
                    if (kbAttr == null) break block85;
                    kbAttr.setBaseValue(1.0);
                    try {
                        for (AttributeModifier mod : kbAttr.getModifiers().toArray(new AttributeModifier[0])) {
                            if (!(mod.getAmount() < 0.0)) continue;
                            kbAttr.removeModifier(mod);
                        }
                    }
                    catch (Throwable activeEffects) {
                    }
                }
                catch (Throwable kbAttr) {
                    {}
                }
            }
            try {
                Entity vehicle;
                if (entity.getVehicle() != null && ((vehicle = entity.getVehicle()).isInvisible() || !vehicle.isAlive())) {
                    entity.stopRiding();
                }
            }
            catch (Throwable vehicle) {
                {}
            }
            try {
                entity.setInvulnerable(false);
            }
            catch (Throwable vehicle) {
                {}
            }
            if (entity instanceof Mob) {
                Mob mob = (Mob)entity;
                try {
                    if (mob.isNoAi()) {
                        mob.setNoAi(false);
                    }
                }
                catch (Throwable activeEffects) {
                }
            }
            try {
                entity.setSilent(false);
            }
            catch (Throwable mob) {
                {}
            }
            if (entity instanceof Player) {
                Player player = (Player)entity;
                try {
                    if (!(player instanceof ServerPlayer)) break block86;
                    ServerPlayer sp = (ServerPlayer)player;
                    sp.getFoodData().setFoodLevel(20);
                    sp.getFoodData().setSaturation(20.0f);
                    sp.getFoodData().setExhaustion(0.0f);
                    try {
                        if (sp.getTicksFrozen() > 0) {
                            sp.setTicksFrozen(0);
                        }
                    }
                    catch (Exception exception) {
                        {}
                    }
                    if (sp.isDeadOrDying() || sp.getPose() == Pose.DYING) {
                        sp.setPose(Pose.STANDING);
                    }
                    try {
                        if (sp.walkDist < 0.0f) {
                            sp.walkDist = 0.0f;
                        }
                    }
                    catch (Exception exception) {
                        {}
                    }
                    try {
                        Entity vehicle;
                        if (sp.isPassenger() && (vehicle = sp.getVehicle()) != null && (vehicle.isInvisible() || !vehicle.isAlive())) {
                            sp.stopRiding();
                        }
                    }
                    catch (Exception exception) {
                        {}
                    }
                }
                catch (Throwable throwable) {
                    {}
                }
            }
        }
    }

    private static void restoreAttribute(LivingEntity entity, Attribute attribute, double minBase) {
        try {
            AttributeInstance attr = entity.getAttribute(attribute);
            if (attr == null) {
                return;
            }
            if (attr.getBaseValue() < minBase) {
                attr.setBaseValue(minBase);
            }
            try {
                for (AttributeModifier mod : attr.getModifiers().toArray(new AttributeModifier[0])) {
                    if (!(mod.getAmount() < 0.0)) continue;
                    attr.removeModifier(mod);
                }
            }
            catch (Throwable throwable) {
            }
        }
        catch (Throwable throwable) {
            {}
        }
    }

    private static void resetSuspiciousEntityData(LivingEntity entity) {
        block10: {
            if (ENTITY_DATA_ITEMS_BY_ID == null) {
                return;
            }
            try {
                Object itemsById = ENTITY_DATA_ITEMS_BY_ID.get(entity.getEntityData());
                if (itemsById == null) {
                    return;
                }
                if (itemsById.getClass().isArray()) {
                    Object[] items;
                    for (Object item : items = (Object[])itemsById) {
                        ImmortalEnforcer.resetDataItemIfSuspicious(entity, item);
                    }
                    break block10;
                }
                try {
                    Method valuesMethod = itemsById.getClass().getMethod("values", new Class[0]);
                    Object values = valuesMethod.invoke(itemsById, new Object[0]);
                    if (values instanceof Iterable) {
                        Iterable iter = (Iterable)values;
                        for (Object item : iter) {
                            ImmortalEnforcer.resetDataItemIfSuspicious(entity, item);
                        }
                    }
                }
                catch (Throwable throwable) {
                    {}
                }
            }
            catch (Throwable e) {
                {}
            }
        }
    }

    private static void resetDataItemIfSuspicious(LivingEntity entity, Object dataItem) {
        block14: {
            if (dataItem == null) {
                return;
            }
            try {
                Integer i;
                Boolean b;
                EntityDataAccessor typedAccessor3;
                Float f;
                Field accessorField = ImmortalEnforcer.findField(dataItem.getClass(), "accessor", "EntityDataAccessor");
                Field valueField = ImmortalEnforcer.findField(dataItem.getClass(), "value", "Object");
                if (accessorField == null || valueField == null) {
                    return;
                }
                Object accessor = accessorField.get(dataItem);
                Object value = valueField.get(dataItem);
                if (DATA_HEALTH_ID_ACCESSOR != null && accessor.equals(DATA_HEALTH_ID_ACCESSOR)) {
                    return;
                }
                if (value instanceof Float && (f = (Float)value).floatValue() != 0.0f && f.floatValue() != Float.MIN_VALUE) {
                    try {
                        typedAccessor3 = (EntityDataAccessor)accessor;
                        entity.getEntityData().set(typedAccessor3, Float.valueOf(Float.MIN_VALUE));
                    }
                    catch (Throwable ignored2) {
                        {}
                    }
                }
                if (value instanceof Boolean && (b = (Boolean)value).booleanValue()) {
                    try {
                        typedAccessor3 = (EntityDataAccessor)accessor;
                        entity.getEntityData().set(typedAccessor3, false);
                    }
                    catch (Throwable ignored3) {
                        {}
                    }
                }
                if (!(value instanceof Integer) || (i = (Integer)value) == 0) break block14;
                if (entity instanceof Player) break block14;
                try {
                    typedAccessor3 = (EntityDataAccessor)accessor;
                    int id = typedAccessor3.getId();
                    if (id > 15) {
                        entity.getEntityData().set(typedAccessor3, 0);
                    }
                }
                catch (Throwable throwable) {}
            }
            catch (Throwable throwable) {
                {}
            }
        }
    }

    private static void resetSuspiciousNBTData(LivingEntity entity) {
        try {
            CompoundTag forgeData = entity.getPersistentData();
            if (forgeData != null && !forgeData.isEmpty()) {
                for (String key : forgeData.getAllKeys().toArray(new String[0])) {
                    try {
                        int val;
                        byte tagType = forgeData.getTagType(key);
                        if (tagType == 5) {
                            float val2 = forgeData.getFloat(key);
                            if (val2 == 0.0f) continue;
                            forgeData.putFloat(key, 0.0f);
                            continue;
                        }
                        if (tagType == 6) {
                            double val3 = forgeData.getDouble(key);
                            if (val3 == 0.0) continue;
                            forgeData.putDouble(key, 0.0);
                            continue;
                        }
                        if (tagType != 3 || (val = forgeData.getInt(key)) == 0) continue;
                        forgeData.putInt(key, 0);
                    }
                    catch (Throwable throwable) {
                        }
                }
            }
        }
        catch (Throwable throwable) {
            {}
        }
        ImmortalEnforcer.resetMixinInjectedFloatFields(entity);
    }

    @SuppressWarnings("unchecked")
    public static void ensurePositiveIntegerData(LivingEntity entity) {
        if (ENTITY_DATA_ITEMS_BY_ID == null) return;
        try {
            Object itemsById = ENTITY_DATA_ITEMS_BY_ID.get(entity.getEntityData());
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
            for (Object item : items) {
                if (item == null) continue;
                try {
                    Field accessorField = ImmortalEnforcer.findField(item.getClass(), "accessor", "EntityDataAccessor");
                    Field valueField = ImmortalEnforcer.findField(item.getClass(), "value", "Object");
                    if (accessorField == null || valueField == null) continue;
                    Object value = valueField.get(item);
                    if (!(value instanceof Integer)) continue;
                    int intVal = (Integer) value;
                    if (intVal > 0) continue;
                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) accessorField.get(item);
                    int id = accessor.getId();
                    if (id <= 15) continue;
                    jp.mikumiku.lal.transformer.EntityMethodHooks.setBypass(true);
                    try {
                        entity.getEntityData().set((EntityDataAccessor<Integer>) accessor, 1);
                    } finally {
                        jp.mikumiku.lal.transformer.EntityMethodHooks.setBypass(false);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void resetMixinInjectedFloatFields(LivingEntity entity) {
        try {
            for (Class<?> clazz = entity.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                    try {
                        String name;
                        if (f.getType() != Float.TYPE || VANILLA_FLOAT_FIELDS.contains(name = f.getName()) || name.contains("speed") || name.contains("Rot") || name.contains("anim") || name.contains("bob") || name.contains("render") || name.contains("alpha") || name.contains("scale") || name.contains("timer") || name.contains("cooldown") || name.contains("Step") || name.contains("distance") || name.contains("Flap") || name.contains("attack") || name.contains("hurt") || name.contains("jump") || name.contains("fly") || name.contains("walk") || name.contains("swim") || name.contains("yaw") || name.contains("pitch") || name.contains("eye") || Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        float val = f.getFloat(entity);
                        if (val == 0.0f || val == Float.MIN_VALUE) continue;
                        f.setFloat(entity, Float.MIN_VALUE);
                    }
                    catch (Throwable throwable) {
                        }
                }
            }
        }
        catch (Throwable throwable) {
            {}
        }
    }

    private static void resetMixinInjectedBooleanFields(LivingEntity entity) {
        try {
            for (Class<?> clazz = entity.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                    try {
                        String name;
                        if (f.getType() != Boolean.TYPE || VANILLA_BOOLEAN_FIELDS.contains(name = f.getName()) || Modifier.isStatic(f.getModifiers()) || name.contains("collision") || name.contains("ground") || name.contains("impulse") || name.contains("portal") || name.contains("invulner") || name.contains("water") || name.contains("snow") || name.contains("physics") || name.contains("culling") || name.contains("dirty") || name.contains("spin") || name.contains("friction") || name.contains("persist") || name.contains("debug") || name.contains("render") || name.contains("visible") || name.contains("loaded") || name.contains("tick") || name.contains("sync") || name.contains("changed")) continue;
                        f.setAccessible(true);
                        boolean val = f.getBoolean(entity);
                        if (!val) continue;
                        f.setBoolean(entity, false);
                    }
                    catch (Throwable throwable) {
                        }
                }
            }
        }
        catch (Throwable throwable) {
            {}
        }
    }

    public static boolean verifyAlive(LivingEntity entity) {
        float actualHealth = ImmortalEnforcer.getHealthField(entity);
        if (actualHealth <= 0.0f) {
            return false;
        }
        boolean dead = ImmortalEnforcer.getDeadField(entity);
        if (dead) {
            return false;
        }
        Entity.RemovalReason reason = ImmortalEnforcer.getRemovalReasonField((Entity)entity);
        return reason == null;
    }

    private static Field findField(Class<?> clazz, String preferredName, String typeHint) {
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField(preferredName);
                f.setAccessible(true);
                return f;
            }
            catch (Throwable throwable) {
                for (Field f : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                    if (!f.getType().getSimpleName().contains(typeHint)) continue;
                    try {
                        f.setAccessible(true);
                        return f;
                    }
                    catch (Throwable throwable2) {
                        }
                }
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(LivingEntity.class, MethodHandles.lookup());
            HEALTH_HANDLE = FieldAccessUtil.findVarHandle(lookup, LivingEntity.class, Float.TYPE, "f_20958_", "health");
            DEATH_TIME_HANDLE = FieldAccessUtil.findVarHandle(lookup, LivingEntity.class, Integer.TYPE, "f_20962_", "deathTime");
            DEAD_HANDLE = FieldAccessUtil.findVarHandle(lookup, LivingEntity.class, Boolean.TYPE, "f_20960_", "dead");
            HURT_TIME_HANDLE = FieldAccessUtil.findVarHandle(lookup, LivingEntity.class, Integer.TYPE, "f_20955_", "hurtTime");
            MethodHandles.Lookup entityLookup = MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup());
            REMOVAL_REASON_HANDLE = FieldAccessUtil.findVarHandle(entityLookup, Entity.class, Entity.RemovalReason.class, "f_146801_", "removalReason");
        }
        catch (Throwable e) {
            {}
        }
        if (HEALTH_HANDLE == null) {
            HEALTH_FIELD = ImmortalEnforcer.findReflectionField(LivingEntity.class, Float.TYPE, "f_20958_", "health");
        }
        if (DEATH_TIME_HANDLE == null) {
            DEATH_TIME_FIELD = ImmortalEnforcer.findReflectionField(LivingEntity.class, Integer.TYPE, "f_20962_", "deathTime");
        }
        if (DEAD_HANDLE == null) {
            DEAD_FIELD = ImmortalEnforcer.findReflectionField(LivingEntity.class, Boolean.TYPE, "f_20960_", "dead");
        }
        if (HURT_TIME_HANDLE == null) {
            HURT_TIME_FIELD = ImmortalEnforcer.findReflectionField(LivingEntity.class, Integer.TYPE, "f_20955_", "hurtTime");
        }
        if (REMOVAL_REASON_HANDLE == null) {
            REMOVAL_REASON_FIELD = ImmortalEnforcer.findReflectionField(Entity.class, Entity.RemovalReason.class, "f_146801_", "removalReason");
        }
        try {
            for (Class clazz = Entity.class; clazz != null; clazz = clazz.getSuperclass()) {
                try {
                    VALID_FIELD = clazz.getDeclaredField("valid");
                    VALID_FIELD.setAccessible(true);
                    break;
                }
                catch (NoSuchFieldException ignored) {
                    {}
                }
            }
        }
        catch (Throwable e) {
            {}
        }
        try {
            Field dhField = null;
            String[] ignored = new String[]{"f_20961_", "DATA_HEALTH_ID"};
            int n = ignored.length;
            for (int i = 0; i < n; ++i) {
                String name2 = ignored[i];
                try {
                    dhField = LivingEntity.class.getDeclaredField(name2);
                    break;
                }
                catch (NoSuchFieldException noSuchFieldException) {
                    continue;
                }
            }
            if (dhField != null) {
                EntityDataAccessor accessor;
                dhField.setAccessible(true);
                DATA_HEALTH_ID_ACCESSOR = accessor = (EntityDataAccessor)dhField.get(null);
            }
        }
        catch (Throwable e) {
            {}
        }
        try {
            for (String name : new String[]{"f_135354_", "itemsById"}) {
                try {
                    ENTITY_DATA_ITEMS_BY_ID = SynchedEntityData.class.getDeclaredField(name);
                    ENTITY_DATA_ITEMS_BY_ID.setAccessible(true);
                    break;
                }
                catch (NoSuchFieldException name2) {
                    {}
                }
            }
            if (ENTITY_DATA_ITEMS_BY_ID == null) {
                for (Field f : FieldAccessUtil.safeGetDeclaredFields(SynchedEntityData.class)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (ft.isArray() && ft.getComponentType().getSimpleName().contains("DataItem")) {
                        f.setAccessible(true);
                        ENTITY_DATA_ITEMS_BY_ID = f;
                        break;
                    }
                    if (!ft.getName().contains("Int2Object")) continue;
                    f.setAccessible(true);
                    ENTITY_DATA_ITEMS_BY_ID = f;
                    break;
                }
            }
        }
        catch (Throwable e) {
            {}
        }
        VANILLA_FLOAT_FIELDS = Set.of("xo", "yo", "zo", "xOld", "yOld", "zOld", "yRot", "xRot", "yRotO", "xRotO", "yBRot", "yBRotO", "fallDistance", "nextFlap", "eyeHeight", "f_19794_", "f_19795_", "f_19796_", "f_19862_", "f_19863_", "f_19791_", "f_19792_", "f_19793_", "f_19799_", "f_19797_", "f_19798_", "f_19858_", "f_19859_", "f_19860_", "f_19861_", "f_19835_", "f_19838_", "health", "lastHurt", "animStep", "animStepO", "yBodyRot", "yBodyRotO", "yHeadRot", "yHeadRotO", "speed", "flyingSpeed", "attackAnim", "oAttackAnim", "animationSpeed", "animationSpeedOld", "animationPosition", "f_20958_", "f_20959_", "f_20956_", "f_20947_", "f_20948_", "f_20949_", "f_20950_", "f_20951_", "f_20952_", "f_20953_", "f_20954_", "f_110151_", "f_267362_", "f_267363_", "f_267364_", "bob", "oBob", "f_36076_", "f_36077_", "jumpMovementFactor");
        VANILLA_BOOLEAN_FIELDS = Set.of("onGround", "horizontalCollision", "verticalCollision", "verticalCollisionBelow", "minorHorizontalCollision", "hurtMarked", "noPhysics", "noCulling", "hasImpulse", "isInsidePortal", "invulnerable", "firstTick", "f_19854_", "f_19855_", "f_19816_", "f_19817_", "f_19818_", "f_19840_", "f_19841_", "f_19819_", "f_19847_", "f_19839_", "f_19846_", "f_19826_", "f_19820_", "f_146813_", "f_146812_", "f_19849_", "wasTouchingWater", "wasEyeInWater", "touchingUnloadedChunk", "isInPowderSnow", "wasInPowderSnow", "f_146873_", "f_146871_", "f_147240_", "dead", "jumping", "effectsDirty", "autoSpinAttack", "discardFriction", "useItem", "f_20960_", "f_20963_", "f_21014_", "f_110152_", "f_20966_", "f_20918_", "reducedDebugInfo", "wasUnderwater", "f_36078_", "f_36079_", "persistenceRequired", "aggressive", "f_21345_", "f_21359_");
    }
}

