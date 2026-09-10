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
import java.util.concurrent.ConcurrentHashMap;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.util.FieldAccessUtil;
import jp.mikumiku.lal.util.MixinUtil;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.entity.EntityInLevelCallback;

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
    private static Field tickListActiveField;
    private static final ConcurrentHashMap<UUID, Object> CALLBACK_BACKUP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, double[]> POSITION_BACKUP = new ConcurrentHashMap<>();
    private static final double POSITION_CORRECTION_THRESHOLD = 16.0;

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
            if (fieldType == Float.TYPE && (n.contains("health") || n.equals("f_20769_"))) {
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
            if (fieldType == Boolean.TYPE && (n.contains("dead") || n.equals("f_20890_"))) {
                try {
                    field.setAccessible(true);
                    return field;
                }
                catch (Throwable throwable) {
                    {}
                }
            }
            if (fieldType != Integer.TYPE) continue;
            if (n.contains("deathtime") || n.contains("death") || n.equals("f_20919_")) {
                try {
                    field.setAccessible(true);
                    return field;
                }
                catch (Throwable throwable) {
                    {}
                }
            }
            if (!n.contains("hurttime") && !n.contains("hurt") && !n.equals("f_20916_")) continue;
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
        if (HEALTH_HANDLE != null && !FieldAccessUtil.isVarHandleCompromised()) {
            HEALTH_HANDLE.set(entity, value);
        }
        if (HEALTH_FIELD != null) {
            try { HEALTH_FIELD.setFloat(entity, value); } catch (Throwable ignored) {}
        }
        if (FieldAccessUtil.HEALTH != null && !FieldAccessUtil.isVarHandleCompromised()) {
            try { FieldAccessUtil.HEALTH.set(entity, value); } catch (Throwable ignored) {}
        }
        try { FieldAccessUtil.unsafePutFloat(entity, HEALTH_FIELD != null ? HEALTH_FIELD : FieldAccessUtil.findAccessibleField(LivingEntity.class, "f_20769_"), value); } catch (Throwable ignored) {}
    }

    private static float getHealthField(LivingEntity entity) {
        if (HEALTH_HANDLE != null && !FieldAccessUtil.isVarHandleCompromised()) {
            return (float) HEALTH_HANDLE.get(entity);
        }
        if (HEALTH_FIELD != null) {
            try { return HEALTH_FIELD.getFloat(entity); } catch (Throwable ignored) {}
        }
        return entity.getHealth();
    }

    private static void setDeathTimeField(LivingEntity entity, int value) {
        if (DEATH_TIME_HANDLE != null && !FieldAccessUtil.isVarHandleCompromised()) {
            DEATH_TIME_HANDLE.set(entity, value);
        }
        if (DEATH_TIME_FIELD != null) {
            try { DEATH_TIME_FIELD.setInt(entity, value); } catch (Throwable ignored) {}
        }
        try { FieldAccessUtil.unsafePutInt(entity, DEATH_TIME_FIELD != null ? DEATH_TIME_FIELD : FieldAccessUtil.findAccessibleField(LivingEntity.class, "f_20919_"), value); } catch (Throwable ignored) {}
    }

    private static void setDeadField(LivingEntity entity, boolean value) {
        if (DEAD_HANDLE != null && !FieldAccessUtil.isVarHandleCompromised()) {
            DEAD_HANDLE.set(entity, value);
        }
        if (DEAD_FIELD != null) {
            try { DEAD_FIELD.setBoolean(entity, value); } catch (Throwable ignored) {}
        }
        try { FieldAccessUtil.unsafePutBoolean(entity, DEAD_FIELD != null ? DEAD_FIELD : FieldAccessUtil.findAccessibleField(LivingEntity.class, "f_20890_"), value); } catch (Throwable ignored) {}
    }

    private static boolean getDeadField(LivingEntity entity) {
        if (DEAD_HANDLE != null && !FieldAccessUtil.isVarHandleCompromised()) {
            return (boolean) DEAD_HANDLE.get(entity);
        }
        if (DEAD_FIELD != null) {
            try { return DEAD_FIELD.getBoolean(entity); } catch (Throwable ignored) {}
        }
        return entity.isDeadOrDying();
    }

    private static void setHurtTimeField(LivingEntity entity, int value) {
        if (HURT_TIME_HANDLE != null && !FieldAccessUtil.isVarHandleCompromised()) {
            HURT_TIME_HANDLE.set(entity, value);
        }
        if (HURT_TIME_FIELD != null) {
            try { HURT_TIME_FIELD.setInt(entity, value); } catch (Throwable ignored) {}
        }
        try { FieldAccessUtil.unsafePutInt(entity, HURT_TIME_FIELD != null ? HURT_TIME_FIELD : FieldAccessUtil.findAccessibleField(LivingEntity.class, "f_20916_"), value); } catch (Throwable ignored) {}
    }

    private static void setRemovalReasonField(Entity entity, Entity.RemovalReason value) {
        if (REMOVAL_REASON_HANDLE != null && !FieldAccessUtil.isVarHandleCompromised()) {
            REMOVAL_REASON_HANDLE.set(entity, value);
        }
        if (REMOVAL_REASON_FIELD != null) {
            try { REMOVAL_REASON_FIELD.set(entity, value); } catch (Throwable ignored) {}
        }
        try { FieldAccessUtil.unsafePutObject(entity, REMOVAL_REASON_FIELD != null ? REMOVAL_REASON_FIELD : FieldAccessUtil.findAccessibleField(Entity.class, "f_146795_"), value); } catch (Throwable ignored) {}
    }

    private static volatile Method sectionEntitiesMethod_IE;
    private static volatile boolean sectionEntitiesMethod_IE_resolved = false;

    private static Method resolveSectionEntitiesMethod(Object section) {
        if (!sectionEntitiesMethod_IE_resolved) {
            synchronized (ImmortalEnforcer.class) {
                if (!sectionEntitiesMethod_IE_resolved) {
                    try {
                        for (Method m : section.getClass().getMethods()) {
                            if (m.getParameterCount() != 0) continue;
                            if (m.getReturnType().getSimpleName().contains("ClassInstanceMultiMap")) {
                                m.setAccessible(true);
                                sectionEntitiesMethod_IE = m;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                    sectionEntitiesMethod_IE_resolved = true;
                }
            }
        }
        return sectionEntitiesMethod_IE;
    }

            /** Fails open: a resolution problem must not disable registration entirely. */
    private static boolean sectionContainsEntity(Object section, Entity entity) {
        try {
            Method m = resolveSectionEntitiesMethod(section);
            if (m == null) return false;
            Object storage = m.invoke(section);
            if (storage instanceof java.util.Collection) {
                return ((java.util.Collection<?>) storage).contains(entity);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Entity.RemovalReason getRemovalReasonField(Entity entity) {
        if (REMOVAL_REASON_HANDLE != null && !FieldAccessUtil.isVarHandleCompromised()) {
            return (Entity.RemovalReason) REMOVAL_REASON_HANDLE.get(entity);
        }
        if (REMOVAL_REASON_FIELD != null) {
            try { return (Entity.RemovalReason) REMOVAL_REASON_FIELD.get(entity); } catch (Throwable ignored) {}
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

    public static void handleImmediateIntrusion(Entity entity) {
        if (entity == null) return;
        UUID uuid = entity.getUUID();
        if (!CombatRegistry.isInImmortalSet(uuid)) return;
        try {
            if (entity instanceof LivingEntity living) {
                enforceImmortality(living);
            }
        } catch (Throwable ignored) {}
        try {
            setRemovalReasonField(entity, null);
        } catch (Throwable ignored) {}
        try {
            entity.noPhysics = false;
        } catch (Throwable ignored) {}
        try {
            entity.setNoGravity(false);
        } catch (Throwable ignored) {}
        try {
            entity.setInvulnerable(true);
        } catch (Throwable ignored) {}
        try {
            if (entity.level() instanceof net.minecraft.server.level.ServerLevel level) {
                ensureInTickList(entity, level);
            }
        } catch (Throwable ignored) {}
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
                            maxHealth = MixinUtil.safeMaxHealth(entity);
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
                                maxHealth = MixinUtil.safeMaxHealth(entity);
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
                        try {
                            if (entity.getArrowCount() > 0) {
                                entity.setArrowCount(0);
                            }
                        } catch (Throwable ignored) {}
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
                            jp.mikumiku.lal.transformer.EntityMethodHooks.setBypass(true);
                            try { entity.setInvulnerable(true); }
                            finally { jp.mikumiku.lal.transformer.EntityMethodHooks.setBypass(false); }
                        } catch (Throwable ignored) {}
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
            try {
                UUID posUuid = entity.getUUID();
                double cx = entity.getX();
                double cy = entity.getY();
                double cz = entity.getZ();
                double[] prev = POSITION_BACKUP.get(posUuid);
                if (prev != null) {
                    double dx = cx - prev[0];
                    double dy = cy - prev[1];
                    double dz = cz - prev[2];
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > POSITION_CORRECTION_THRESHOLD * POSITION_CORRECTION_THRESHOLD && !(entity instanceof Player)) {
                        try {
                            entity.teleportTo(prev[0], prev[1], prev[2]);
                        } catch (Throwable t1) {
                            try {
                                entity.moveTo(prev[0], prev[1], prev[2], entity.getYRot(), entity.getXRot());
                            } catch (Throwable t2) {
                                try {
                                    entity.setPosRaw(prev[0], prev[1], prev[2]);
                                } catch (Throwable ignored) {}
                            }
                        }
                    } else {
                        prev[0] = cx;
                        prev[1] = cy;
                        prev[2] = cz;
                    }
                } else {
                    POSITION_BACKUP.put(posUuid, new double[]{cx, cy, cz});
                }
            } catch (Throwable ignored) {}
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
                if (value instanceof Float && (f = (Float)value).floatValue() != 0.0f) {
                    try {
                        typedAccessor3 = (EntityDataAccessor)accessor;
                        // Exact zero: Float.MIN_VALUE is the smallest positive denormal, so it reads as
                        // alive, unlike the Boolean->false and Integer->0 handled below.
                        entity.getEntityData().set(typedAccessor3, Float.valueOf(0.0f));
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
                        if (val == 0.0f) continue;
                        // Exact zero: Float.MIN_VALUE is a positive denormal and reads as alive.
                        f.setFloat(entity, 0.0f);
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

    /**
     * Memoised per class, hits and misses: this runs per synched-data item per enforcement pass and
     * otherwise walks the hierarchy, throwing on every miss.
     */
    private static final Object FIND_FIELD_MISS = new Object();
    private static final ClassValue<java.util.concurrent.ConcurrentHashMap<String, Object>> FIND_FIELD_CACHE =
            new ClassValue<>() {
                @Override
                protected java.util.concurrent.ConcurrentHashMap<String, Object> computeValue(Class<?> type) {
                    return new java.util.concurrent.ConcurrentHashMap<>();
                }
            };

    private static Field findField(Class<?> clazz, String preferredName, String typeHint) {
        if (clazz == null) return null;
        java.util.concurrent.ConcurrentHashMap<String, Object> cache;
        try {
            cache = FIND_FIELD_CACHE.get(clazz);
        } catch (Throwable t) {
            return findFieldUncached(clazz, preferredName, typeHint);
        }
        String key = preferredName + ':' + typeHint;
        Object cached = cache.get(key);
        if (cached != null) {
            return cached == FIND_FIELD_MISS ? null : (Field) cached;
        }
        Field found = findFieldUncached(clazz, preferredName, typeHint);
        cache.put(key, found != null ? found : FIND_FIELD_MISS);
        return found;
    }

    private static Field findFieldUncached(Class<?> clazz, String preferredName, String typeHint) {
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
        MethodHandles.Lookup lookup = null;
        try {
            lookup = MethodHandles.privateLookupIn(LivingEntity.class, MethodHandles.lookup());
        } catch (Throwable e) {}
        if (lookup != null) {
            try { HEALTH_HANDLE = FieldAccessUtil.findVarHandle(lookup, LivingEntity.class, Float.TYPE, "f_20769_", "health"); } catch (Throwable e) {}
            try { DEATH_TIME_HANDLE = FieldAccessUtil.findVarHandle(lookup, LivingEntity.class, Integer.TYPE, "f_20919_", "deathTime"); } catch (Throwable e) {}
            try { DEAD_HANDLE = FieldAccessUtil.findVarHandle(lookup, LivingEntity.class, Boolean.TYPE, "f_20890_", "dead"); } catch (Throwable e) {}
            try { HURT_TIME_HANDLE = FieldAccessUtil.findVarHandle(lookup, LivingEntity.class, Integer.TYPE, "f_20916_", "hurtTime"); } catch (Throwable e) {}
        }
        try {
            MethodHandles.Lookup entityLookup = MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup());
            REMOVAL_REASON_HANDLE = FieldAccessUtil.findVarHandle(entityLookup, Entity.class, Entity.RemovalReason.class, "f_146795_", "removalReason");
        } catch (Throwable e) {}
        if (HEALTH_HANDLE == null) {
            HEALTH_FIELD = ImmortalEnforcer.findReflectionField(LivingEntity.class, Float.TYPE, "f_20769_", "health");
        }
        if (DEATH_TIME_HANDLE == null) {
            DEATH_TIME_FIELD = ImmortalEnforcer.findReflectionField(LivingEntity.class, Integer.TYPE, "f_20919_", "deathTime");
        }
        if (DEAD_HANDLE == null) {
            DEAD_FIELD = ImmortalEnforcer.findReflectionField(LivingEntity.class, Boolean.TYPE, "f_20890_", "dead");
        }
        if (HURT_TIME_HANDLE == null) {
            HURT_TIME_FIELD = ImmortalEnforcer.findReflectionField(LivingEntity.class, Integer.TYPE, "f_20916_", "hurtTime");
        }
        if (REMOVAL_REASON_HANDLE == null) {
            REMOVAL_REASON_FIELD = ImmortalEnforcer.findReflectionField(Entity.class, Entity.RemovalReason.class, "f_146795_", "removalReason");
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
            for (String name : new String[]{"f_135345_", "itemsById"}) {
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
        try {
            for (String fieldName : new String[]{"active", "f_156903_", "passive", "f_156904_"}) {
                try {
                    Field f = net.minecraft.world.level.entity.EntityTickList.class.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    if (tickListActiveField == null && (fieldName.equals("active") || fieldName.equals("f_156903_"))) {
                        tickListActiveField = f;
                    }
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}
        VANILLA_FLOAT_FIELDS = Set.of("xo", "yo", "zo", "xOld", "yOld", "zOld", "yRot", "xRot", "yRotO", "xRotO", "yBRot", "yBRotO", "fallDistance", "nextFlap", "eyeHeight", "f_19854_", "f_19855_", "f_19856_", "f_19790_", "f_19791_", "f_19792_", "f_19857_", "f_19858_", "f_19859_", "f_19860_", "f_19789_", "f_19816_", "f_19793_", "f_19829_", "f_19787_", "f_19867_", "f_19788_", "health", "f_20769_", "lastHurt", "animStep", "animStepO", "yBodyRot", "yBodyRotO", "yHeadRot", "yHeadRotO", "speed", "flyingSpeed", "attackAnim", "oAttackAnim", "animationSpeed", "animationSpeedOld", "animationPosition", "f_20898_", "f_20894_", "f_20895_", "f_20883_", "f_20884_", "f_20885_", "f_20886_", "f_20953_", "f_20921_", "f_20920_", "f_20955_", "f_20931_", "f_20932_", "bob", "oBob", "f_36100_", "f_36099_", "jumpMovementFactor");
        VANILLA_BOOLEAN_FIELDS = Set.of("onGround", "horizontalCollision", "verticalCollision", "verticalCollisionBelow", "minorHorizontalCollision", "hurtMarked", "noPhysics", "noCulling", "hasImpulse", "isInsidePortal", "invulnerable", "firstTick", "f_19861_", "f_19862_", "f_19863_", "f_201939_", "f_185931_", "f_19864_", "f_19794_", "f_19811_", "f_19812_", "f_19817_", "f_19840_", "f_19803_", "f_19798_", "f_19800_", "f_146808_", "f_146809_", "f_146813_", "wasTouchingWater", "wasEyeInWater", "touchingUnloadedChunk", "isInPowderSnow", "wasInPowderSnow", "dead", "jumping", "effectsDirty", "autoSpinAttack", "discardFriction", "useItem", "f_20890_", "f_20899_", "f_20948_", "f_147183_", "f_20911_", "reducedDebugInfo", "wasUnderwater", "f_36076_", "f_36085_", "persistenceRequired", "aggressive", "f_21353_");
    }

    private static Field entityManagerField_IE = null;
    private static boolean entityManagerField_IE_resolved = false;
    private static Field entityLookupField_IE = null;
    private static boolean entityLookupField_IE_resolved = false;
    private static Field lookupByIdField_IE = null;
    private static boolean lookupByIdField_IE_resolved = false;
    private static Field lookupByUuidField_IE = null;
    private static boolean lookupByUuidField_IE_resolved = false;
    private static Field knownUuidsField_IE = null;
    private static boolean knownUuidsField_IE_resolved = false;
    private static Field sectionStorageField_IE = null;
    private static boolean sectionStorageField_IE_resolved = false;
    private static Method sectionStorageGetOrCreateSection_IE = null;
    private static boolean sectionStorageGetOrCreate_IE_resolved = false;
    private static Method sectionAddMethod_IE = null;
    private static boolean sectionAddMethod_IE_resolved = false;
    private static Field levelCallbackField_IE = null;
    private static boolean levelCallbackField_IE_resolved = false;
    private static Field callbacksField_IE = null;
    private static boolean callbacksField_IE_resolved = false;
    private static Field chunkMapEntityMapField_IE = null;
    private static boolean chunkMapEntityMapField_IE_resolved = false;
    private static Method chunkMapAddEntityMethod_IE = null;
    private static boolean chunkMapAddEntityMethod_IE_resolved = false;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void ensureEntityRegistration(Entity entity, ServerLevel level) {
        if (entity == null || level == null) return;
        UUID uuid = entity.getUUID();
        if (!CombatRegistry.isInImmortalSet(uuid)) return;
        try {
            if (!entityManagerField_IE_resolved) {
                entityManagerField_IE_resolved = true;
                for (String name : new String[]{"f_143244_", "entityManager"}) {
                    try {
                        entityManagerField_IE = ServerLevel.class.getDeclaredField(name);
                        entityManagerField_IE.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (entityManagerField_IE == null) return;
            Object entityManager = entityManagerField_IE.get(level);
            if (entityManager == null) return;

            if (!entityLookupField_IE_resolved) {
                entityLookupField_IE_resolved = true;
                for (String name : new String[]{"f_157494_", "visibleEntityStorage", "f_157496_"}) {
                    try {
                        entityLookupField_IE = entityManager.getClass().getDeclaredField(name);
                        entityLookupField_IE.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (entityLookupField_IE == null) {
                    for (Field f : FieldAccessUtil.safeGetDeclaredFields(entityManager.getClass())) {
                        if (f.getType().getSimpleName().contains("EntityLookup")) {
                            f.setAccessible(true);
                            entityLookupField_IE = f;
                            break;
                        }
                    }
                }
            }
            if (entityLookupField_IE == null) return;
            Object entityLookup = entityLookupField_IE.get(entityManager);
            if (entityLookup == null) return;

            if (!lookupByIdField_IE_resolved) {
                lookupByIdField_IE_resolved = true;
                for (String name : new String[]{"f_156807_", "byId"}) {
                    try {
                        lookupByIdField_IE = entityLookup.getClass().getDeclaredField(name);
                        lookupByIdField_IE.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (lookupByIdField_IE == null) {
                    for (Field f : FieldAccessUtil.safeGetDeclaredFields(entityLookup.getClass())) {
                        if (f.getType().getName().contains("Int2Object")) {
                            f.setAccessible(true);
                            lookupByIdField_IE = f;
                            break;
                        }
                    }
                }
            }
            if (!lookupByUuidField_IE_resolved) {
                lookupByUuidField_IE_resolved = true;
                for (String name : new String[]{"f_156808_", "byUuid"}) {
                    try {
                        lookupByUuidField_IE = entityLookup.getClass().getDeclaredField(name);
                        lookupByUuidField_IE.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (lookupByUuidField_IE == null) {
                    for (Field f : FieldAccessUtil.safeGetDeclaredFields(entityLookup.getClass())) {
                        if (!Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            lookupByUuidField_IE = f;
                            break;
                        }
                    }
                }
            }

            if (lookupByIdField_IE != null) {
                Object byIdMap = lookupByIdField_IE.get(entityLookup);
                if (byIdMap != null) {
                    try {
                        Method containsKey = byIdMap.getClass().getMethod("containsKey", int.class);
                        boolean contains = (boolean) containsKey.invoke(byIdMap, entity.getId());
                        if (!contains) {
                            Method put = byIdMap.getClass().getMethod("put", int.class, Object.class);
                            put.invoke(byIdMap, entity.getId(), entity);
                        }
                    } catch (Throwable ignored) {
                        try {
                            Method containsKey = byIdMap.getClass().getMethod("containsKey", Object.class);
                            boolean contains = (boolean) containsKey.invoke(byIdMap, entity.getId());
                            if (!contains) {
                                Method put = byIdMap.getClass().getMethod("put", Object.class, Object.class);
                                put.invoke(byIdMap, entity.getId(), entity);
                            }
                        } catch (Throwable ignored2) {}
                    }
                }
            }

            if (lookupByUuidField_IE != null) {
                Object byUuidMap = lookupByUuidField_IE.get(entityLookup);
                if (byUuidMap instanceof Map) {
                    Map map = (Map) byUuidMap;
                    if (!map.containsKey(uuid)) {
                        map.put(uuid, entity);
                    }
                }
            }

            if (!knownUuidsField_IE_resolved) {
                knownUuidsField_IE_resolved = true;
                for (String name : new String[]{"f_157491_", "knownUuids"}) {
                    try {
                        knownUuidsField_IE = entityManager.getClass().getDeclaredField(name);
                        knownUuidsField_IE.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (knownUuidsField_IE == null) {
                    for (Field f : FieldAccessUtil.safeGetDeclaredFields(entityManager.getClass())) {
                        if (!Modifier.isStatic(f.getModifiers()) && Set.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            knownUuidsField_IE = f;
                            break;
                        }
                    }
                }
            }
            if (knownUuidsField_IE != null) {
                Object knownUuids = knownUuidsField_IE.get(entityManager);
                if (knownUuids instanceof Set) {
                    Set uuidSet = (Set) knownUuids;
                    if (!uuidSet.contains(uuid)) {
                        uuidSet.add(uuid);
                    }
                }
            }

            if (!sectionStorageField_IE_resolved) {
                sectionStorageField_IE_resolved = true;
                for (String name : new String[]{"f_157495_", "sectionStorage"}) {
                    try {
                        sectionStorageField_IE = entityManager.getClass().getDeclaredField(name);
                        sectionStorageField_IE.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (sectionStorageField_IE == null) {
                    for (Field f : FieldAccessUtil.safeGetDeclaredFields(entityManager.getClass())) {
                        if (!Modifier.isStatic(f.getModifiers()) && f.getType().getSimpleName().contains("EntitySectionStorage")) {
                            f.setAccessible(true);
                            sectionStorageField_IE = f;
                            break;
                        }
                    }
                }
            }
            if (sectionStorageField_IE != null) {
                Object sectionStorage = sectionStorageField_IE.get(entityManager);
                if (sectionStorage != null) {
                    if (!sectionStorageGetOrCreate_IE_resolved) {
                        sectionStorageGetOrCreate_IE_resolved = true;
                        for (String name : new String[]{"m_156893_", "getOrCreateSection"}) {
                            try {
                                sectionStorageGetOrCreateSection_IE = sectionStorage.getClass().getMethod(name, long.class);
                                break;
                            } catch (NoSuchMethodException ignored) {}
                        }
                    }
                    if (!sectionAddMethod_IE_resolved) {
                        sectionAddMethod_IE_resolved = true;
                        for (String name : new String[]{"m_188346_", "add"}) {
                            try {
                                Class<?> sectionClass = sectionStorage.getClass();
                                for (Method m : sectionClass.getMethods()) {
                                    if ((m.getName().equals("m_188346_") || m.getName().equals("add"))
                                            && m.getParameterCount() == 1) {
                                        sectionAddMethod_IE = m;
                                        break;
                                    }
                                }
                                break;
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (sectionStorageGetOrCreateSection_IE != null) {
                        try {
                            int sx = SectionPos.blockToSectionCoord(entity.getBlockX());
                            int sy = SectionPos.blockToSectionCoord(entity.getBlockY());
                            int sz = SectionPos.blockToSectionCoord(entity.getBlockZ());
                            long sectionKey = SectionPos.asLong(sx, sy, sz);
                            Object section = sectionStorageGetOrCreateSection_IE.invoke(sectionStorage, sectionKey);
                            // Membership is tested first, like the byId/byUuid/knownUuids writes
                            // beside it: this runs every enforcement pass and the section storage
                            // appends without deduplicating.
                            if (section != null && sectionAddMethod_IE != null
                                    && !sectionContainsEntity(section, entity)) {
                                sectionAddMethod_IE.invoke(section, entity);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            try {
                Object chunkMap = level.getChunkSource().chunkMap;
                if (chunkMap != null) {
                    if (!chunkMapEntityMapField_IE_resolved) {
                        chunkMapEntityMapField_IE_resolved = true;
                        for (String name : new String[]{"f_140150_", "entityMap"}) {
                            try {
                                chunkMapEntityMapField_IE = chunkMap.getClass().getDeclaredField(name);
                                chunkMapEntityMapField_IE.setAccessible(true);
                                break;
                            } catch (NoSuchFieldException ignored) {}
                        }
                        if (chunkMapEntityMapField_IE == null) {
                            for (Field f : FieldAccessUtil.safeGetDeclaredFields(chunkMap.getClass())) {
                                if (!Modifier.isStatic(f.getModifiers()) && f.getType().getName().contains("Int2Object")) {
                                    f.setAccessible(true);
                                    chunkMapEntityMapField_IE = f;
                                    break;
                                }
                            }
                        }
                    }
                    if (chunkMapEntityMapField_IE != null) {
                        Object entityMap = chunkMapEntityMapField_IE.get(chunkMap);
                        if (entityMap != null) {
                            boolean inChunkMap = false;
                            try {
                                Method containsKey = entityMap.getClass().getMethod("containsKey", int.class);
                                inChunkMap = (boolean) containsKey.invoke(entityMap, entity.getId());
                            } catch (Throwable t) {
                                try {
                                    Method containsKey = entityMap.getClass().getMethod("containsKey", Object.class);
                                    inChunkMap = (boolean) containsKey.invoke(entityMap, entity.getId());
                                } catch (Throwable ignored) {}
                            }
                            if (!inChunkMap) {
                                if (!chunkMapAddEntityMethod_IE_resolved) {
                                    chunkMapAddEntityMethod_IE_resolved = true;
                                    for (String name : new String[]{"m_140174_", "addEntity"}) {
                                        try {
                                            chunkMapAddEntityMethod_IE = chunkMap.getClass().getDeclaredMethod(name, Entity.class);
                                            chunkMapAddEntityMethod_IE.setAccessible(true);
                                            break;
                                        } catch (NoSuchMethodException ignored) {}
                                    }
                                }
                                if (chunkMapAddEntityMethod_IE != null) {
                                    try {
                                        chunkMapAddEntityMethod_IE.invoke(chunkMap, entity);
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            if (!levelCallbackField_IE_resolved) {
                levelCallbackField_IE_resolved = true;
                for (String name : new String[]{"f_146801_", "levelCallback"}) {
                    try {
                        levelCallbackField_IE = Entity.class.getDeclaredField(name);
                        levelCallbackField_IE.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (levelCallbackField_IE != null) {
                Object cb = levelCallbackField_IE.get(entity);
                boolean isNull = (cb == null);
                boolean isNullCb = false;
                boolean isStub = false;
                if (!isNull) {
                    try {
                        isNullCb = (cb == EntityInLevelCallback.NULL);
                    } catch (Throwable ignored) {}
                    if (!isNullCb) {
                        String cbClass = cb.getClass().getName();
                        if (!cbClass.startsWith("net.minecraft.") && !cbClass.startsWith("com.mojang.")) {
                            Object backup = CALLBACK_BACKUP.get(uuid);
                            if (backup != null && backup != cb && backup != EntityInLevelCallback.NULL) {
                                isStub = true;
                            }
                        }
                    }
                }
                if (isNull || isNullCb || isStub) {
                    Object restored = CALLBACK_BACKUP.get(uuid);
                    if (restored != null && restored != EntityInLevelCallback.NULL) {
                        levelCallbackField_IE.set(entity, restored);
                    } else {
                        try {
                            if (!callbacksField_IE_resolved) {
                                callbacksField_IE_resolved = true;
                                for (String name : new String[]{"f_157492_", "callbacks", "levelCallback"}) {
                                    try {
                                        callbacksField_IE = entityManager.getClass().getDeclaredField(name);
                                        callbacksField_IE.setAccessible(true);
                                        break;
                                    } catch (NoSuchFieldException ignored) {}
                                }
                            }
                            if (callbacksField_IE != null) {
                                Object levelCallbackImpl = callbacksField_IE.get(entityManager);
                                if (levelCallbackImpl != null && levelCallbackImpl instanceof EntityInLevelCallback) {
                                    levelCallbackField_IE.set(entity, levelCallbackImpl);
                                    CALLBACK_BACKUP.put(uuid, levelCallbackImpl);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } else if (cb != null && cb != EntityInLevelCallback.NULL) {
                    String cbClass = cb.getClass().getName();
                    if (cbClass.startsWith("net.minecraft.") || cbClass.startsWith("com.mojang.")) {
                        CALLBACK_BACKUP.put(uuid, cb);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    public static void ensureInTickList(Entity entity, net.minecraft.server.level.ServerLevel level) {
        try {
            if (!CombatRegistry.isInImmortalSet(entity.getUUID())) return;
            Object tl = level.entityTickList;
            if (tl == null) return;
            if (tickListActiveField == null) return;
            Object map = tickListActiveField.get(tl);
            if (map == null) return;
            java.lang.reflect.Method containsKey = map.getClass().getMethod("containsKey", int.class);
            boolean exists = (boolean) containsKey.invoke(map, entity.getId());
            if (!exists) {
                java.lang.reflect.Method put = map.getClass().getMethod("put", int.class, Object.class);
                put.invoke(map, entity.getId(), entity);
            }
        } catch (Throwable ignored) {}
    }

    public static void cleanupCallbackBackup(UUID uuid) {
        CALLBACK_BACKUP.remove(uuid);
        POSITION_BACKUP.remove(uuid);
    }

    public static void cleanupStaleCallbackBackups() {
        try {
            CALLBACK_BACKUP.entrySet().removeIf(entry ->
                    !CombatRegistry.isInImmortalSet(entry.getKey()));
        } catch (Throwable ignored) {}
        try {
            POSITION_BACKUP.entrySet().removeIf(entry ->
                    !CombatRegistry.isInImmortalSet(entry.getKey()));
        } catch (Throwable ignored) {}
    }
}
