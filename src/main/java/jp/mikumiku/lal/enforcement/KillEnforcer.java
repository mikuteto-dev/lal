package jp.mikumiku.lal.enforcement;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import jp.mikumiku.lal.agent.LALAgentBridge;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.core.EntityLedger;
import jp.mikumiku.lal.core.EntityLedgerEntry;
import jp.mikumiku.lal.core.LifecycleState;
import jp.mikumiku.lal.damage.LALDamageSources;
import jp.mikumiku.lal.enforcement.RegistryCleaner;
import jp.mikumiku.lal.util.FieldAccessUtil;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.IEventBus;
import sun.misc.Unsafe;

public class KillEnforcer {
    private static VarHandle HEALTH_HANDLE;
    private static VarHandle DEATH_TIME_HANDLE;
    private static VarHandle DEAD_HANDLE;
    private static VarHandle REMOVAL_REASON_HANDLE;
    private static Field HEALTH_FIELD;
    private static Field DEATH_TIME_FIELD;
    private static Field DEAD_FIELD;
    private static Field REMOVAL_REASON_FIELD;
    private static Method DROP_ALL_DEATH_LOOT;
    private static Method GET_EXPERIENCE_REWARD;
    private static MethodHandle ENTITY_SET_REMOVED;
    private static Field ENTITY_DATA_ITEMS_BY_ID;
    private static final IEventBus CAPTURED_EVENT_BUS;

    public KillEnforcer() {
        super();
    }

    private static Field findFieldByType(Class<?> clazz, Class<?> fieldType, String ... names) {
        for (String name : names) {
            try {
                Field f2 = clazz.getDeclaredField(name);
                f2.setAccessible(true);
                return f2;
            }
            catch (Throwable f2) {
            }
        }
        for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != fieldType) continue;
            if (fieldType == Entity.RemovalReason.class) {
                try {
                    f.setAccessible(true);
                    return f;
                }
                catch (Throwable f2) {
                }
            }
            String name = f.getName().toLowerCase();
            if (fieldType == Float.TYPE && (name.contains("health") || name.equals("f_20958_"))) {
                try {
                    f.setAccessible(true);
                    return f;
                }
                catch (Throwable throwable) {
                }
            }
            if (fieldType == Integer.TYPE && (name.contains("deathtime") || name.contains("death") || name.equals("f_20962_"))) {
                try {
                    f.setAccessible(true);
                    return f;
                }
                catch (Throwable throwable) {
                }
            }
            if (fieldType != Boolean.TYPE || !name.contains("dead") && !name.equals("f_20960_")) continue;
            try {
                f.setAccessible(true);
                return f;
            }
            catch (Throwable throwable) {
            }
        }
        return null;
    }

    private static void setHealth(LivingEntity target, float value) {
        if (HEALTH_HANDLE != null) {
            HEALTH_HANDLE.set(target, value);
            return;
        }
        if (HEALTH_FIELD != null) {
            try {
                HEALTH_FIELD.setFloat(target, value);
            }
            catch (Throwable throwable) {
            }
        }
    }

    private static float getHealth(LivingEntity target) {
        if (HEALTH_HANDLE != null) {
            return (float) HEALTH_HANDLE.get(target);
        }
        if (HEALTH_FIELD != null) {
            try {
                return HEALTH_FIELD.getFloat(target);
            }
            catch (Throwable throwable) {
            }
        }
        return target.getHealth();
    }

    private static void setDeathTime(LivingEntity target, int value) {
        if (DEATH_TIME_HANDLE != null) {
            DEATH_TIME_HANDLE.set(target, value);
            return;
        }
        if (DEATH_TIME_FIELD != null) {
            try {
                DEATH_TIME_FIELD.setInt(target, value);
            }
            catch (Throwable throwable) {
            }
        }
    }

    private static void setDead(LivingEntity target, boolean value) {
        if (DEAD_HANDLE != null) {
            DEAD_HANDLE.set(target, value);
            return;
        }
        if (DEAD_FIELD != null) {
            try {
                DEAD_FIELD.setBoolean(target, value);
            }
            catch (Throwable throwable) {
            }
        }
    }

    private static boolean getDead(LivingEntity target) {
        if (DEAD_HANDLE != null) {
            return (boolean) DEAD_HANDLE.get(target);
        }
        if (DEAD_FIELD != null) {
            try {
                return DEAD_FIELD.getBoolean(target);
            }
            catch (Throwable throwable) {
            }
        }
        return target.isDeadOrDying();
    }

    private static void setRemovalReason(Entity target, Entity.RemovalReason value) {
        if (REMOVAL_REASON_HANDLE != null) {
            REMOVAL_REASON_HANDLE.set(target, value);
            return;
        }
        if (REMOVAL_REASON_FIELD != null) {
            try {
                REMOVAL_REASON_FIELD.set(target, value);
            }
            catch (Throwable throwable) {
            }
        }
    }

    private static Entity.RemovalReason getRemovalReason(Entity target) {
        if (REMOVAL_REASON_HANDLE != null) {
            return (Entity.RemovalReason) REMOVAL_REASON_HANDLE.get(target);
        }
        if (REMOVAL_REASON_FIELD != null) {
            try {
                return (Entity.RemovalReason)REMOVAL_REASON_FIELD.get(target);
            }
            catch (Throwable throwable) {
            }
        }
        return target.getRemovalReason();
    }

    private static boolean hasHealthAccess() {
        return HEALTH_HANDLE != null || HEALTH_FIELD != null;
    }

    private static boolean hasDeadAccess() {
        return DEAD_HANDLE != null || DEAD_FIELD != null;
    }

    private static boolean hasRemovalAccess() {
        return REMOVAL_REASON_HANDLE != null || REMOVAL_REASON_FIELD != null;
    }

    private static Method findMethod(Class<?> clazz, String[] names, Class<?> ... paramTypes) {
        for (String name : names) {
            try {
                Method m = clazz.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            }
            catch (Throwable throwable) {
            }
        }
        return null;
    }

    public static void forceKill(LivingEntity target, ServerLevel level, @Nullable Entity attacker) {
        DamageSource source;
        UUID uuid;
        block48: {
            uuid = target.getUUID();
            if (CombatRegistry.isDeadConfirmed(uuid)) {
                return;
            }
            KillEnforcer.setRemovalReason((Entity)target, null);
            int tick = level.getServer().getTickCount();
            UUID attackerUuid = attacker != null ? attacker.getUUID() : null;
            CombatRegistry.addToKillSet(uuid, attackerUuid, tick);
            target.setInvulnerable(false);
            target.invulnerableTime = 0;
            target.setNoGravity(false);
            if (target instanceof ServerPlayer) {
                ServerPlayer sp = (ServerPlayer)target;
                sp.getAbilities().invulnerable = false;
            }
            if (target instanceof WitherBoss) {
                WitherBoss wither = (WitherBoss)target;
                wither.setInvulnerableTicks(0);
            }

            EntityMethodHooks.setBypass(true);
            try {
                DamageSource lalSource = attacker != null
                    ? LALDamageSources.lalAttack(level, attacker)
                    : LALDamageSources.lalAttack(level);

                target.invulnerableTime = 0;
                target.hurt(lalSource, Float.MAX_VALUE);
                if (!target.isDeadOrDying() && !target.isRemoved()) {
                    target.invulnerableTime = 0;
                    target.hurt(lalSource, 100000.0f);
                }
                if (!target.isDeadOrDying() && !target.isRemoved()) {
                    target.invulnerableTime = 0;
                    target.hurt(target.damageSources().fellOutOfWorld(), 100000.0f);
                }
                if (!target.isDeadOrDying() && !target.isRemoved()) {
                    target.invulnerableTime = 0;
                    target.hurt(target.damageSources().genericKill(), 100000.0f);
                }
                if (!target.isDeadOrDying() && !target.isRemoved()) {
                    target.kill();
                }
                if (!target.isDeadOrDying() && !target.isRemoved()) {
                    target.setHealth(0.0f);
                    target.die(lalSource);
                }

                boolean vanillaDied = target.isDeadOrDying() || target.isRemoved();
                EntityMethodHooks.setBypass(false);

                if (vanillaDied) {
                    try { ((Entity)target).setSilent(true); } catch (Throwable ignored2) {}
                    try {
                        target.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
                    } catch (Throwable ignored2) {}
                    try {
                        level.broadcastEntityEvent((Entity)target, (byte)3);
                    } catch (Throwable ignored2) {}
                    try {
                        level.broadcastEntityEvent((Entity)target, (byte)60);
                    } catch (Throwable ignored2) {}

                    KillEnforcer.cleanupBossEvents((Entity)target);
                    KillEnforcer.setLastHurtByPlayer(target, attacker);

                    KillEnforcer.silentServerRemove((Entity)target, level);

                    CombatRegistry.confirmDead(uuid);
                    return;
                }
            } catch (Throwable ignored) {
                EntityMethodHooks.setBypass(false);
            }

            try {
                AttributeInstance maxHealthAttr = target.getAttribute(Attributes.MAX_HEALTH);
                if (maxHealthAttr == null) break block48;
                try {
                    for (AttributeModifier mod : maxHealthAttr.getModifiers().toArray(new AttributeModifier[0])) {
                        maxHealthAttr.removeModifier(mod);
                    }
                }
                catch (Throwable throwable) {
                }
                maxHealthAttr.setBaseValue(0.0);
            }
            catch (Throwable maxHealthAttr) {
            }
        }
        try {
            AttributeInstance armorToughnessAttr;
            AttributeInstance armorAttr = target.getAttribute(Attributes.ARMOR);
            if (armorAttr != null) {
                armorAttr.setBaseValue(0.0);
            }
            if ((armorToughnessAttr = target.getAttribute(Attributes.ARMOR_TOUGHNESS)) != null) {
                armorToughnessAttr.setBaseValue(0.0);
            }
        }
        catch (Throwable armorAttr) {
        }
        try {
            target.removeAllEffects();
        }
        catch (Throwable armorAttr) {
        }
        try {
            target.stopRiding();
            target.unRide();
            target.getPassengers().forEach(Entity::stopRiding);
        }
        catch (Throwable armorAttr) {
        }
        if (target instanceof Mob) {
            Mob mob = (Mob)target;
            try {
                mob.dropLeash(true, false);
                mob.setTarget(null);
            }
            catch (Throwable armorToughnessAttr) {
            }
        }
        CombatRegistry.setForcedHealth(uuid, 0.0f);
        EntityLedgerEntry entry = EntityLedger.get().getOrCreate(uuid);
        entry.state = LifecycleState.PENDING_KILL;
        try {
            target.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
        }
        catch (Throwable armorToughnessAttr) {
        }
        try {
            for (int i = 0; i < 8; ++i) {
                target.setHealth(0.0f);
            }
        }
        catch (Throwable i) {
        }
        KillEnforcer.setHealth(target, 0.0f);
        KillEnforcer.setDead(target, false);
        KillEnforcer.setRemovalReason((Entity)target, null);
        KillEnforcer.corruptAllHealthFields(target);
        KillEnforcer.corruptSynchedEntityData(target);
        KillEnforcer.setLastHurtByPlayer(target, attacker);
        boolean isFirstKill = !CombatRegistry.hasDroppedLoot(uuid);
        DamageSource damageSource = source = attacker != null ? LALDamageSources.lalAttack(level, attacker) : LALDamageSources.lalAttack(level);
        if (isFirstKill) {
            CombatRegistry.markDroppedLoot(uuid);
            KillEnforcer.setHealth(target, 1.0f);
            KillEnforcer.setDead(target, false);
            KillEnforcer.setRemovalReason((Entity)target, null);
            EntityMethodHooks.setBypass(true);
            try {
                target.die(source);
            }
            catch (Throwable e) {
            }
            boolean dieBodyRan = KillEnforcer.getDead(target);
            EntityMethodHooks.setBypass(false);
            KillEnforcer.triggerKillCriteria(target, source, attacker, level);
            if (!dieBodyRan) {
                KillEnforcer.manualDropLoot(target, source, attacker, level);
            }
            try {
                target.gameEvent(GameEvent.ENTITY_DIE);
            }
            catch (Throwable throwable) {
            }
            try {
                level.broadcastEntityEvent((Entity)target, (byte)3);
            }
            catch (Throwable throwable) {
            }
        }
        KillEnforcer.setHealth(target, 0.0f);
        KillEnforcer.setDead(target, true);
        KillEnforcer.setDeathTime(target, 1);
        try {
            target.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
        }
        catch (Throwable dieBodyRan) {
        }
        try {
            target.getEntityData().set(Entity.DATA_POSE, Pose.DYING);
        }
        catch (Throwable dieBodyRan) {
        }
        try {
        }
        catch (Throwable dieBodyRan) {
        }
        target.hurtTime = 0;
        if (target instanceof Mob) {
            Mob mob = (Mob)target;
            try {
                mob.goalSelector.getAvailableGoals().forEach(wrappedGoal -> wrappedGoal.stop());
                mob.goalSelector.removeAllGoals(g -> true);
            }
            catch (Throwable throwable) {
            }
            try {
                mob.targetSelector.getAvailableGoals().forEach(wrappedGoal -> wrappedGoal.stop());
                mob.targetSelector.removeAllGoals(g -> true);
            }
            catch (Throwable throwable) {
            }
            try {
                mob.setTarget(null);
                mob.setNoAi(true);
            }
            catch (Throwable throwable) {
            }
        }
        try {
            target.noPhysics = true;
        }
        catch (Throwable mob) {
        }
        KillEnforcer.persistDeathState(target);
        KillEnforcer.cleanupBossEvents((Entity)target);

        try { ((Entity)target).setSilent(true); } catch (Throwable ignored) {}

        try {
            target.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
        } catch (Throwable ignored) {}
        Level level2 = target.level();
        if (level2 instanceof ServerLevel) {
            ServerLevel sl2 = (ServerLevel)level2;
            try { sl2.broadcastEntityEvent((Entity)target, (byte)3); } catch (Throwable ignored) {}

            KillEnforcer.silentServerRemove((Entity)target, sl2);
        }

        KillEnforcer.restoreEventBus();
    }

    private static void corruptAllHealthFields(LivingEntity target) {
        try {
            for (Class<?> clazz = target.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Class<?> type = f.getType();
                        if (type == Float.TYPE) {
                            if (clazz == LivingEntity.class || clazz == Entity.class) continue;
                            f.setFloat(target, Float.MIN_VALUE);
                            continue;
                        }
                        if (type == Double.TYPE) {
                            if (clazz == LivingEntity.class || clazz == Entity.class) continue;
                            f.setDouble(target, Double.MIN_VALUE);
                            continue;
                        }
                        if (type == Boolean.TYPE) {
                            String name = f.getName().toLowerCase();
                            if (name.contains("dead") || name.contains("die") || name.contains("kill")
                                    || name.contains("dying") || name.contains("destroy")
                                    || name.contains("discard") || name.contains("remov")) {
                                f.setBoolean(target, true);
                                continue;
                            }
                            if (name.contains("alive") || name.contains("valid") || name.contains("invul")
                                    || name.contains("protect") || name.contains("immortal")
                                    || name.contains("muteki") || name.contains("novel")
                                    || name.contains("safe") || name.contains("movable")
                                    || name.contains("invincible") || name.contains("fumetsu")
                                    || name.contains("canupdate") || name.contains("active")
                                    || name.contains("exist")) {
                                f.setBoolean(target, false);
                                continue;
                            }
                            continue;
                        }
                        if (type == Integer.TYPE) {
                            String name = f.getName().toLowerCase();
                            if (name.contains("novel") || name.contains("muteki")
                                    || name.contains("protect") || name.contains("immortal")
                                    || name.contains("safe") || name.contains("invincible")) {
                                f.setInt(target, 0);
                            }
                            continue;
                        }
                        if (clazz == LivingEntity.class || clazz == Entity.class) continue;
                        if (Map.class.isAssignableFrom(type)) {
                            try {
                                Map map = (Map)f.get(target);
                                if (map != null) map.clear();
                            }
                            catch (Throwable throwable) {}
                        }
                    }
                    catch (Throwable throwable) {
                    }
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void corruptHealthFieldsLight(LivingEntity target) {
        try {
            for (Class<?> clazz = target.getClass(); clazz != null && clazz != LivingEntity.class && clazz != Entity.class; clazz = clazz.getSuperclass()) {
                for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                    try {
                        f.setAccessible(true);
                        Class<?> type = f.getType();
                        if (type == Float.TYPE) {
                            f.setFloat(target, Float.MIN_VALUE);
                            continue;
                        }
                        if (type != Double.TYPE) continue;
                        f.setDouble(target, Double.MIN_VALUE);
                    }
                    catch (Throwable throwable) {
                    }
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void corruptSynchedEntityData(LivingEntity target) {
        block10: {
            if (ENTITY_DATA_ITEMS_BY_ID == null) {
                return;
            }
            try {
                Object itemsById = ENTITY_DATA_ITEMS_BY_ID.get(target.getEntityData());
                if (itemsById == null) {
                    return;
                }
                if (itemsById.getClass().isArray()) {
                    Object[] items;
                    for (Object item : items = (Object[])itemsById) {
                        KillEnforcer.corruptDataItem(target, item);
                    }
                    break block10;
                }
                try {
                    Method valuesMethod = itemsById.getClass().getMethod("values", new Class[0]);
                    Object values = valuesMethod.invoke(itemsById, new Object[0]);
                    if (values instanceof Iterable) {
                        Iterable iter = (Iterable)values;
                        for (Object item : iter) {
                            KillEnforcer.corruptDataItem(target, item);
                        }
                    }
                }
                catch (Throwable throwable) {
                }
            }
            catch (Throwable e) {
            }
        }
    }

    private static void corruptDataItem(LivingEntity target, Object dataItem) {
        if (dataItem == null) {
            return;
        }
        try {
            Field valueField = null;
            Field accessorField = null;
            for (Field f : KillEnforcer.safeGetDeclaredFields(dataItem.getClass())) {
                f.setAccessible(true);
                if ((f.getName().equals("value") || f.getType() == Object.class) && valueField == null) {
                    valueField = f;
                }
                if (!f.getName().equals("accessor") && !f.getType().getSimpleName().contains("EntityDataAccessor") || accessorField != null) continue;
                accessorField = f;
            }
            if (valueField == null || accessorField == null) {
                return;
            }
            Object value = valueField.get(dataItem);
            Object accessor = accessorField.get(dataItem);
            if (value instanceof Float) {
                try {
                    EntityDataAccessor typedAccessor = (EntityDataAccessor)accessor;
                    target.getEntityData().set(typedAccessor, Float.valueOf(Float.MIN_VALUE));
                }
                catch (Throwable typedAccessor) {}
            } else if (value instanceof Boolean) {
                try {
                    EntityDataAccessor typedAccessor = (EntityDataAccessor)accessor;
                    target.getEntityData().set(typedAccessor, false);
                }
                catch (Throwable throwable) {}
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void persistDeathState(LivingEntity target) {
        try {
            CompoundTag forgeData = target.getPersistentData();
            forgeData.putBoolean("lal_dead", true);
            forgeData.putFloat("Health", 0.0f);
            forgeData.putInt("DeathTime", 20);
        }
        catch (Throwable throwable) {
        }
    }

    private static void setLastHurtByPlayer(LivingEntity target, @Nullable Entity attacker) {
        if (!(attacker instanceof Player)) {
            return;
        }
        Player player = (Player)attacker;
        try {
            Field f2;
            for (String name : new String[]{"lastHurtByPlayer", "f_20956_"}) {
                try {
                    f2 = LivingEntity.class.getDeclaredField(name);
                    f2.setAccessible(true);
                    f2.set(target, player);
                    break;
                }
                catch (NoSuchFieldException ignored) {
                }
            }
            for (String name : new String[]{"lastHurtByPlayerTime", "f_20957_"}) {
                try {
                    f2 = LivingEntity.class.getDeclaredField(name);
                    f2.setAccessible(true);
                    f2.setInt(target, 100);
                    break;
                }
                catch (NoSuchFieldException noSuchFieldException) {
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void triggerKillCriteria(LivingEntity target, DamageSource source, @Nullable Entity attacker, ServerLevel level) {
        block8: {
            try {
                if (!(attacker instanceof ServerPlayer)) break block8;
                ServerPlayer sp = (ServerPlayer)attacker;
                try {
                    sp.awardStat(Stats.ENTITY_KILLED.get(target.getType()));
                }
                catch (Throwable throwable) {
                }
                try {
                    CriteriaTriggers.PLAYER_KILLED_ENTITY.trigger(sp, (Entity)target, source);
                }
                catch (Throwable throwable) {
                }
                try {
                    sp.awardKillScore((Entity)target, 0, source);
                }
                catch (Throwable throwable) {}
            }
            catch (Throwable throwable) {
            }
        }
    }

    private static void manualDropLoot(LivingEntity target, DamageSource source, @Nullable Entity attacker, ServerLevel level) {
        if (DROP_ALL_DEATH_LOOT != null) {
            try {
                EntityMethodHooks.setBypass(true);
                try {
                    DROP_ALL_DEATH_LOOT.invoke((Object)target, source);
                    KillEnforcer.spawnExperience(target, level);
                    return;
                }
                finally {
                    EntityMethodHooks.setBypass(false);
                }
            }
            catch (Throwable e) {
            }
        }
        try {
            ResourceLocation lootTableId = null;
            if (target instanceof Mob) {
                Mob mob = (Mob)target;
                try {
                    lootTableId = mob.getLootTable();
                }
                catch (Throwable throwable) {
                }
            }
            if (lootTableId == null) {
                lootTableId = target.getType().getDefaultLootTable();
            }
            if (lootTableId == null || lootTableId.getPath().equals("empty")) {
                return;
            }
            LootTable table = level.getServer().getLootData().getLootTable(lootTableId);
            LootParams.Builder builder = new LootParams.Builder(level).withParameter(LootContextParams.THIS_ENTITY, target).withParameter(LootContextParams.ORIGIN, target.position()).withParameter(LootContextParams.DAMAGE_SOURCE, source);
            if (attacker != null) {
                builder.withOptionalParameter(LootContextParams.KILLER_ENTITY, attacker);
                builder.withOptionalParameter(LootContextParams.DIRECT_KILLER_ENTITY, attacker);
            }
            if (attacker instanceof Player) {
                Player player = (Player)attacker;
                builder.withOptionalParameter(LootContextParams.LAST_DAMAGE_PLAYER, player);
                builder.withLuck(player.getLuck());
            }
            LootParams params = builder.create(LootContextParamSets.ENTITY);
            table.getRandomItems(params).forEach(item -> {
                if (!item.isEmpty()) {
                    ItemEntity itemEntity = new ItemEntity((Level)level, target.getX(), target.getY() + 0.5, target.getZ(), item);
                    itemEntity.setDefaultPickUpDelay();
                    level.addFreshEntity((Entity)itemEntity);
                }
            });
        }
        catch (Throwable e) {
        }
        KillEnforcer.spawnExperience(target, level);
    }

    private static void spawnExperience(LivingEntity target, ServerLevel level) {
        try {
            int xp = 0;
            if (GET_EXPERIENCE_REWARD != null) {
                try {
                    xp = (Integer)GET_EXPERIENCE_REWARD.invoke((Object)target, new Object[0]);
                }
                catch (Throwable throwable) {
                }
            }
            if (xp <= 0 && target instanceof Mob) {
                xp = 5;
            }
            if (xp > 0) {
                ExperienceOrb.award((ServerLevel)level, (Vec3)target.position(), (int)xp);
            }
        }
        catch (Throwable e) {
        }
    }

    public static void initiateKill(LivingEntity target, ServerLevel level) {
        UUID attackerUuid = CombatRegistry.getKillAttacker(target.getUUID());
        Entity attacker = attackerUuid != null ? level.getEntity(attackerUuid) : null;
        KillEnforcer.forceKill(target, level, attacker);
    }

    public static void enforceDeathState(LivingEntity target) {
        KillEnforcer.setHealth(target, 0.0f);
        KillEnforcer.setDead(target, true);
        try {
            target.setHealth(0.0f);
        }
        catch (Throwable throwable) {
        }
        KillEnforcer.setRemovalReason((Entity)target, null);
        try {
            target.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
        }
        catch (Throwable throwable) {
        }
        try {
            AttributeInstance maxHealthAttr = target.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealthAttr != null && maxHealthAttr.getBaseValue() > 0.0) {
                maxHealthAttr.setBaseValue(0.0);
            }
        }
        catch (Throwable maxHealthAttr) {
        }
        CombatRegistry.setForcedHealth(target.getUUID(), 0.0f);
        int currentDeathTime = KillEnforcer.getDeathTime(target);
        if (currentDeathTime < 60 && currentDeathTime < 1) {
            KillEnforcer.setDeathTime(target, 1);
        }
        try {
            target.setPose(Pose.DYING);
        }
        catch (Throwable throwable) {
        }
        target.noPhysics = true;
        if (currentDeathTime >= 60) {
            try {
                target.setBoundingBox(new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
            }
            catch (Throwable throwable) {
            }
        }
        target.setInvulnerable(false);
        target.invulnerableTime = 0;
        if (target instanceof Mob) {
            Mob mob = (Mob)target;
            try {
                mob.setNoAi(true);
            }
            catch (Throwable throwable) {
            }
        }
        if (currentDeathTime >= 60) {
            try {
                Level level = target.level();
                if (level instanceof ServerLevel) {
                    ServerLevel sl = (ServerLevel)level;
                    KillEnforcer.removeFromTickList((Entity)target, sl);
                    KillEnforcer.removeFromLevelCollections((Entity)target, sl);
                }
            }
            catch (Throwable throwable) {
            }
        }
        KillEnforcer.corruptAllHealthFields(target);
    }

    public static int getDeathTime(LivingEntity target) {
        if (DEATH_TIME_HANDLE != null) {
            try {
                return (int) DEATH_TIME_HANDLE.get(target);
            }
            catch (Throwable throwable) {
            }
        }
        if (DEATH_TIME_FIELD != null) {
            try {
                return DEATH_TIME_FIELD.getInt(target);
            }
            catch (Throwable throwable) {
            }
        }
        return target.deathTime;
    }

    public static void executeRemoval(LivingEntity target, ServerLevel level) {
        block22: {
            try {
                EntityInLevelCallback cb = target.levelCallback;
                if (cb != null) {
                    cb.onRemove(Entity.RemovalReason.KILLED);
                }
            }
            catch (Throwable cb) {
            }
            KillEnforcer.sendRemovePacketToTrackers((Entity)target, level);
            try {
                jp.mikumiku.lal.network.LALNetwork.broadcastRemoveEntity(level, target.getId());
            } catch (Throwable ignored) {}
            KillEnforcer.removeFromTickList((Entity)target, level);
            KillEnforcer.removeFromLevelCollections((Entity)target, level);
            try {
                level.getScoreboard().entityRemoved((Entity)target);
            }
            catch (Throwable cb) {
            }
            try {
                target.updateDynamicGameEventListener(DynamicGameEventListener::remove);
            }
            catch (Throwable cb) {
            }
            if (target instanceof Mob) {
                Mob mob = (Mob)target;
                try {
                    level.navigatingMobs.remove(mob);
                }
                catch (Throwable throwable) {
                }
            }
            KillEnforcer.cleanupBossEvents((Entity)target);
            try {
                target.levelCallback = EntityInLevelCallback.NULL;
            }
            catch (Throwable mob) {
            }
            try {
                target.discard();
            }
            catch (Throwable mob) {
            }
            KillEnforcer.setRemovalReason((Entity)target, Entity.RemovalReason.KILLED);
            if (!(target instanceof ServerPlayer)) {
                RegistryCleaner.deleteFromAllRegistries((Entity)target, level);
            }
            try {
                target.setBoundingBox(new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
                target.noPhysics = true;
            }
            catch (Throwable mob) {
            }
            KillEnforcer.purgeExternalState((Entity)target);
            KillEnforcer.restoreEventBus();
            try {
                Entity check = level.getEntity(target.getUUID());
                if (check == null) break block22;
                try {
                    EntityInLevelCallback cb = check.levelCallback;
                    if (cb != null && cb != EntityInLevelCallback.NULL) {
                        cb.onRemove(Entity.RemovalReason.KILLED);
                    }
                }
                catch (Throwable throwable) {
                }
                KillEnforcer.sendRemovePacketToTrackers(check, level);
            }
            catch (Throwable throwable) {
            }
        }
    }

    public static void executeKill(LivingEntity target, ServerLevel level) {
        KillEnforcer.initiateKill(target, level);
        KillEnforcer.executeRemoval(target, level);
    }

    private static void sendRemovePacketToTrackers(Entity target, ServerLevel level) {
        try {
            Object seenBy;
            Field seenByField;
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            Object tracked = null;
            try {
                tracked = chunkMap.entityMap.remove(target.getId());
            }
            catch (Throwable e2) {
                try {
                    Method removeMethod = chunkMap.entityMap.getClass().getMethod("remove", Integer.TYPE);
                    tracked = removeMethod.invoke((Object)chunkMap.entityMap, target.getId());
                }
                catch (Throwable removeMethod) {
                }
            }
            if (tracked != null && (seenByField = KillEnforcer.findAccessibleField(tracked.getClass(), "seenBy")) != null && (seenBy = seenByField.get(tracked)) instanceof Set) {
                Set connections = (Set)seenBy;
                ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(new int[]{target.getId()});
                for (Object conn : connections) {
                    try {
                        if (!(conn instanceof ServerPlayerConnection)) continue;
                        ServerPlayerConnection spc = (ServerPlayerConnection)conn;
                        spc.getPlayer().connection.send((Packet)removePacket);
                    }
                    catch (Throwable throwable) {}
                }
            }
            try {
                ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(new int[]{target.getId()});
                for (ServerPlayer player : level.players()) {
                    try {
                        player.connection.send((Packet)removePacket);
                    }
                    catch (Throwable throwable) {}
                }
            }
            catch (Throwable throwable) {
            }
        }
        catch (Throwable e) {
        }
    }

    private static void silentServerRemove(Entity target, ServerLevel level) {
        try {
            if (target instanceof Mob) {
                Mob mob = (Mob) target;
                try { mob.setNoAi(true); } catch (Throwable ignored) {}
                try { mob.setTarget(null); } catch (Throwable ignored) {}
            }

            target.noPhysics = true;

            try {
                target.levelCallback = EntityInLevelCallback.NULL;
            } catch (Throwable ignored) {}

            level.getServer().tell(new net.minecraft.server.TickTask(
                level.getServer().getTickCount() + 1,
                () -> {
                    try { KillEnforcer.removeFromTickList(target, level); } catch (Throwable ignored) {}
                    if (target instanceof Mob) {
                        try { level.navigatingMobs.remove((Mob) target); } catch (Throwable ignored) {}
                    }
                    try { level.getScoreboard().entityRemoved(target); } catch (Throwable ignored) {}
                    if (target instanceof Mob) {
                        Mob mob = (Mob) target;
                        try { mob.goalSelector.removeAllGoals(g -> true); } catch (Throwable ignored) {}
                        try { mob.targetSelector.removeAllGoals(g -> true); } catch (Throwable ignored) {}
                    }
                    if (target.isMultipartEntity()) {
                        try {
                            net.minecraftforge.entity.PartEntity<?>[] parts = target.getParts();
                            if (parts != null) {
                                for (net.minecraftforge.entity.PartEntity<?> part : parts) {
                                    try { level.dragonParts.remove(part.getId()); } catch (Throwable ignored) {}
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            ));
        } catch (Throwable ignored) {}
    }

    private static void removeFromTickList(Entity target, ServerLevel level) {
        try {
            try {
                level.entityTickList.remove(target);
            }
            catch (Throwable throwable) {
            }
            for (String fieldName : new String[]{"active", "passive", "f_156918_", "f_156919_"}) {
                try {
                    Field f = EntityTickList.class.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object map = f.get(level.entityTickList);
                    if (map == null) continue;
                    try {
                        map.getClass().getMethod("remove", Integer.TYPE).invoke(map, target.getId());
                    }
                    catch (Throwable throwable) {}
                }
                catch (NoSuchFieldException noSuchFieldException) {
                }
            }
        }
        catch (Throwable e) {
        }
    }

    private static void cleanupBossEvents(Entity target) {
        block17: {
            try {
                for (Class<?> clazz = target.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                    for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                        try {
                            if (!ServerBossEvent.class.isAssignableFrom(f.getType()) && !f.getType().getSimpleName().contains("BossEvent") && !f.getType().getSimpleName().contains("BossBar")) continue;
                            f.setAccessible(true);
                            Object bossEvent = f.get(target);
                            if (bossEvent instanceof ServerBossEvent) {
                                ServerBossEvent sbe = (ServerBossEvent)bossEvent;
                                sbe.removeAllPlayers();
                                sbe.setVisible(false);
                                continue;
                            }
                            if (bossEvent == null) continue;
                            try {
                                Method removeAll = bossEvent.getClass().getMethod("removeAllPlayers", new Class[0]);
                                removeAll.invoke(bossEvent, new Object[0]);
                            }
                            catch (Throwable removeAll) {
                            }
                            try {
                                Method setVisible = bossEvent.getClass().getMethod("setVisible", Boolean.TYPE);
                                setVisible.invoke(bossEvent, false);
                            }
                            catch (Throwable setVisible) {}
                        }
                        catch (Throwable throwable) {
                        }
                    }
                }
            }
            catch (Throwable clazz) {
            }
            try {
                ServerLevel sl;
                CustomBossEvents customBossEvents;
                Level targetLevel = target.level();
                if (!(targetLevel instanceof ServerLevel) || (customBossEvents = (sl = (ServerLevel)targetLevel).getServer().getCustomBossEvents()) == null) break block17;
                try {
                    Method getEvents = customBossEvents.getClass().getMethod("getEvents", new Class[0]);
                    Object events = getEvents.invoke((Object)customBossEvents, new Object[0]);
                    if (events instanceof Iterable) {
                        Iterable iter = (Iterable)events;
                        for (Object event : iter) {
                            ServerBossEvent sbe;
                            if (!(event instanceof ServerBossEvent) || !(sbe = (ServerBossEvent)event).getName().getString().contains(String.valueOf(target.getId()))) continue;
                            sbe.removeAllPlayers();
                            sbe.setVisible(false);
                        }
                    }
                }
                catch (Throwable throwable) {
                }
            }
            catch (Throwable throwable) {
            }
        }
    }

    private static Field findAccessibleField(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            }
            catch (Throwable throwable) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private static Method findAccessibleMethod(Class<?> clazz, String name, Class<?> ... params) {
        while (clazz != null && clazz != Object.class) {
            try {
                Method m = clazz.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            }
            catch (Throwable throwable) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    public static boolean verifyKill(LivingEntity target) {
        Entity.RemovalReason reason;
        boolean dead;
        float actualHealth;
        int deathTime = KillEnforcer.getDeathTime(target);
        if (deathTime > 0 && deathTime < 60) {
            return false;
        }
        boolean healthZero = false;
        boolean isDead = false;
        if (KillEnforcer.hasHealthAccess() && (actualHealth = KillEnforcer.getHealth(target)) <= 0.0f) {
            healthZero = true;
        }
        if (KillEnforcer.hasDeadAccess() && (dead = KillEnforcer.getDead(target))) {
            isDead = true;
        }
        if (healthZero && isDead) {
            return true;
        }
        if (KillEnforcer.hasRemovalAccess() && (reason = KillEnforcer.getRemovalReason((Entity)target)) != null && (healthZero || isDead)) {
            return true;
        }
        try {
            float dataHealth = ((Float)target.getEntityData().get(LivingEntity.DATA_HEALTH_ID)).floatValue();
            if (dataHealth <= 0.0f) {
                return true;
            }
        }
        catch (Throwable throwable) {
        }
        return false;
    }

    private static void purgeExternalState(Entity target) {
        try {
            KillEnforcer.purgeBackingObjects(target);
            KillEnforcer.purgeStaticMaps(target);
            KillEnforcer.nullifyModFields(target);
        }
        catch (Throwable e) {
        }
    }

    private static void purgeBackingObjects(Entity target) {
        try {
            Class<?> entityClass = target.getClass();
            String entityPackage = entityClass.getPackageName();
            if (entityPackage.startsWith("net.minecraft.")) {
                return;
            }
            String modPackagePrefix = KillEnforcer.getModPackagePrefix(entityPackage);
            System.out.println("[LAL] purgeBackingObjects: entity=" + entityClass.getName() + " pkg=" + modPackagePrefix);
            ArrayList<Object> backingObjects = new ArrayList<Object>();
            Set seen = Collections.newSetFromMap(new IdentityHashMap());
            for (Class<?> scanClass = entityClass; scanClass != null && scanClass != Object.class && !scanClass.getName().startsWith("net.minecraft."); scanClass = scanClass.getSuperclass()) {
                for (Field f : KillEnforcer.safeGetDeclaredFields(scanClass)) {
                    Class<?> fieldType;
                    if (Modifier.isStatic(f.getModifiers()) || (fieldType = f.getType()).isPrimitive() || fieldType.isEnum() || fieldType == String.class || fieldType.getPackageName().startsWith("java.") || fieldType.getPackageName().startsWith("net.minecraft.")) continue;
                    try {
                        f.setAccessible(true);
                        Object backingObj = f.get(target);
                        if (backingObj == null || !seen.add(backingObj)) continue;
                        System.out.println("[LAL] Found backing object: field=" + f.getName() + " type=" + backingObj.getClass().getName());
                        backingObjects.add(backingObj);
                    }
                    catch (Throwable throwable) {
                    }
                }
            }
            Set<Class<?>> searchClasses = KillEnforcer.collectModClasses(modPackagePrefix, entityClass);
            for (Object e : backingObjects) {
                Set<Class<?>> boClasses = KillEnforcer.collectModClasses(modPackagePrefix, e.getClass());
                searchClasses.addAll(boClasses);
            }
            System.out.println("[LAL] searchClasses count=" + searchClasses.size() + ", backingObjects count=" + backingObjects.size());
            for (Object e : backingObjects) {
                KillEnforcer.markAsRemoved(e);
            }
            for (Object e : backingObjects) {
                for (Class<?> searchClass : searchClasses) {
                    KillEnforcer.removeFromStaticCollections(e, searchClass);
                }
                for (Class<?> searchClass : searchClasses) {
                    KillEnforcer.forceZeroListsContaining(e, searchClass);
                }
            }
            for (Object e : backingObjects) {
                KillEnforcer.clearBossBarFields(e);
                KillEnforcer.nullifyObjectFields(e);
                KillEnforcer.nullifyWorldReferences(e);
            }
            for (Class clazz : searchClasses) {
                KillEnforcer.removeFromStaticCollections(target, clazz);
            }
            KillEnforcer.deletePersistenceFiles(target);
        }
        catch (Throwable throwable) {
        }
    }

    private static String getModPackagePrefix(String packageName) {
        String[] parts = packageName.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return packageName;
    }

    private static Set<Class<?>> collectModClasses(String modPackagePrefix, Class<?> entityClass) {
        LinkedHashSet classes = new LinkedHashSet();
        classes.add(entityClass);
        try {
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst != null) {
                for (Class clazz : inst.getAllLoadedClasses()) {
                    if (!clazz.getName().startsWith(modPackagePrefix)) continue;
                    classes.add(clazz);
                }
                if (classes.size() > 1) {
                    return classes;
                }
            }
        }
        catch (Throwable inst) {
        }
        HashSet<Class> visited = new HashSet<Class>();
        ArrayDeque queue = new ArrayDeque();
        queue.add(entityClass);
        while (!queue.isEmpty() && visited.size() < 50) {
            Class current = (Class)queue.poll();
            if (!visited.add(current)) continue;
            classes.add(current);
            Class superClass = current.getSuperclass();
            if (superClass != null && superClass.getName().startsWith(modPackagePrefix)) {
                queue.add(superClass);
            }
            try {
                for (Field field : KillEnforcer.safeGetDeclaredFields(current)) {
                    Class<?> ft = field.getType();
                    if (!ft.getName().startsWith(modPackagePrefix) || visited.contains(ft)) continue;
                    queue.add(ft);
                }
            }
            catch (Throwable throwable) {
            }
            try {
                for (Method method : current.getDeclaredMethods()) {
                    Class<?> rt = method.getReturnType();
                    if (rt.getName().startsWith(modPackagePrefix) && !visited.contains(rt)) {
                        queue.add(rt);
                    }
                    for (Class<?> pt : method.getParameterTypes()) {
                        if (!pt.getName().startsWith(modPackagePrefix) || visited.contains(pt)) continue;
                        queue.add(pt);
                    }
                }
            }
            catch (Throwable throwable) {
            }
        }
        try {
            URL uRL;
            CodeSource cs;
            ProtectionDomain pd = entityClass.getProtectionDomain();
            if (pd != null && (cs = pd.getCodeSource()) != null && (uRL = cs.getLocation()) != null) {
                classes.addAll(KillEnforcer.scanClassesFromCodeSource(uRL, modPackagePrefix, entityClass.getClassLoader()));
            }
        }
        catch (Throwable pd) {
        }
        try {
            ClassLoader cl = entityClass.getClassLoader();
            if (cl != null) {
                String packagePath = modPackagePrefix.replace('.', '/');
                Enumeration<URL> enumeration = cl.getResources(packagePath);
                while (enumeration.hasMoreElements()) {
                    URL resourceUrl = enumeration.nextElement();
                    classes.addAll(KillEnforcer.scanClassesFromResource(resourceUrl, modPackagePrefix, cl));
                }
            }
        }
        catch (Throwable cl) {
        }
        if (classes.size() <= 3) {
            try {
                String classFile = entityClass.getName().replace('.', '/') + ".class";
                URL classUrl = entityClass.getClassLoader().getResource(classFile);
                if (classUrl != null) {
                    int bangIdx;
                    String string = classUrl.toString();
                    String jarPath = null;
                    if (string.startsWith("union:")) {
                        int rawHashIdx;
                        String filePart = string.substring("union:".length());
                        int n = filePart.indexOf("%23");
                        if (n > 0) {
                            filePart = filePart.substring(0, n);
                        }
                        if ((rawHashIdx = filePart.indexOf(35)) > 0) {
                            filePart = filePart.substring(0, rawHashIdx);
                        }
                        if (filePart.endsWith("!/")) {
                            filePart = filePart.substring(0, filePart.length() - 2);
                        } else if (filePart.endsWith("!")) {
                            filePart = filePart.substring(0, filePart.length() - 1);
                        }
                        jarPath = filePart;
                    } else if (string.startsWith("jar:file:") && (bangIdx = (jarPath = string.substring("jar:file:".length())).indexOf(33)) > 0) {
                        jarPath = jarPath.substring(0, bangIdx);
                    }
                    if (jarPath != null) {
                        Set<Class<?>> set;
                        File jarFile;
                        if (jarPath.length() > 2 && jarPath.charAt(0) == '/' && jarPath.charAt(2) == ':') {
                            jarPath = jarPath.substring(1);
                        }
                        if ((jarFile = new File(jarPath)).isFile() && jarPath.endsWith(".jar") && !(set = KillEnforcer.scanClassesFromCodeSource(jarFile.toURI().toURL(), modPackagePrefix, entityClass.getClassLoader())).isEmpty()) {
                            classes.addAll(set);
                        }
                    }
                }
            }
            catch (Throwable throwable) {
            }
        }
        return classes;
    }

    private static Set<Class<?>> scanClassesFromCodeSource(URL codeSourceUrl, String packagePrefix, ClassLoader cl) {
        LinkedHashSet found;
        block23: {
            found = new LinkedHashSet();
            try {
                File file;
                String urlStr = codeSourceUrl.toString();
                String path = null;
                if (urlStr.startsWith("union:")) {
                    int rawHashIdx;
                    String filePart = urlStr.substring("union:".length());
                    int hashIdx = filePart.indexOf("%23");
                    if (hashIdx > 0) {
                        filePart = filePart.substring(0, hashIdx);
                    }
                    if ((rawHashIdx = filePart.indexOf(35)) > 0) {
                        filePart = filePart.substring(0, rawHashIdx);
                    }
                    if (filePart.endsWith("!/")) {
                        filePart = filePart.substring(0, filePart.length() - 2);
                    } else if (filePart.endsWith("!")) {
                        filePart = filePart.substring(0, filePart.length() - 1);
                    }
                    path = filePart;
                } else {
                    try {
                        path = codeSourceUrl.toURI().getPath();
                    }
                    catch (Throwable e) {
                        path = codeSourceUrl.getPath();
                    }
                }
                if (path == null) {
                    return found;
                }
                if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
                    path = path.substring(1);
                }
                if ((file = new File(path)).isFile() && path.endsWith(".jar")) {
                    try (JarFile jar = new JarFile(file);){
                        Enumeration<JarEntry> entries = jar.entries();
                        String packagePath = packagePrefix.replace('.', '/');
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String entryName = entry.getName();
                            if (!entryName.startsWith(packagePath) || !entryName.endsWith(".class")) continue;
                            String className = entryName.replace('/', '.').replace(".class", "");
                            if (className.startsWith("org.objectweb.asm.") || className.startsWith("org.spongepowered.asm.") || className.startsWith("org.apache.") || className.startsWith("com.google.gson.") || className.startsWith("org.slf4j.")) continue;
                            try {
                                Class<?> c = Class.forName(className, false, cl);
                                found.add(c);
                            }
                            catch (Throwable throwable) {}
                        }
                        break block23;
                    }
                }
                if (file.isDirectory()) {
                    KillEnforcer.scanDirectory(file, packagePrefix, cl, found);
                }
            }
            catch (Throwable throwable) {
            }
        }
        return found;
    }

    private static void scanDirectory(File dir, String packagePrefix, ClassLoader cl, Set<Class<?>> found) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        String packagePath = packagePrefix.replace('.', File.separatorChar);
        for (File f : files) {
            String absPath;
            int pkgIdx;
            if (f.isDirectory()) {
                KillEnforcer.scanDirectory(f, packagePrefix, cl, found);
                continue;
            }
            if (!f.getName().endsWith(".class") || (pkgIdx = (absPath = f.getAbsolutePath()).indexOf(packagePath)) < 0) continue;
            String className = absPath.substring(pkgIdx).replace(File.separatorChar, '.').replace(".class", "");
            try {
                Class<?> c = Class.forName(className, false, cl);
                found.add(c);
            }
            catch (Throwable throwable) {
            }
        }
    }

    private static Set<Class<?>> scanClassesFromResource(URL resourceUrl, String packagePrefix, ClassLoader cl) {
        LinkedHashSet found = new LinkedHashSet();
        try {
            File dir;
            String protocol = resourceUrl.getProtocol();
            if ("jar".equals(protocol)) {
                String jarPath = resourceUrl.getPath();
                if (jarPath.contains("!")) {
                    File jarFile;
                    if ((jarPath = jarPath.substring(0, jarPath.indexOf(33))).startsWith("file:")) {
                        jarPath = new URI(jarPath).getPath();
                    }
                    if ((jarFile = new File(jarPath)).exists()) {
                        found.addAll(KillEnforcer.scanClassesFromCodeSource(jarFile.toURI().toURL(), packagePrefix, cl));
                    }
                }
            } else if ("file".equals(protocol) && (dir = new File(resourceUrl.toURI())).isDirectory()) {
                String[] prefixParts = packagePrefix.split("\\.");
                File root = dir;
                for (int i = 0; i < prefixParts.length && (root = root.getParentFile()) != null; ++i) {
                }
                if (root != null) {
                    KillEnforcer.scanDirectory(root, packagePrefix, cl, found);
                }
            }
        }
        catch (Throwable throwable) {
        }
        return found;
    }

    private static void nullifyObjectFields(Object obj) {
        try {
            for (Class<?> clazz = obj.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Class<?> type = f.getType();
                        String name = f.getName().toLowerCase();
                        if (type == Boolean.TYPE) {
                            if (name.contains("remov") || name.contains("dead") || name.contains("die") || name.contains("kill") || name.contains("destroy") || name.contains("discard")) {
                                f.setBoolean(obj, true);
                                continue;
                            }
                            if (!name.contains("alive") && !name.contains("active") && !name.contains("valid") && !name.contains("exist")) continue;
                            f.setBoolean(obj, false);
                            continue;
                        }
                        if (type == Float.TYPE || type == Double.TYPE) {
                            if (!name.contains("health") && !name.contains("hp") && !name.contains("life") && !name.contains("armor") && !name.contains("shield") && !name.contains("protect")) continue;
                            if (type == Float.TYPE) {
                                f.setFloat(obj, 0.0f);
                                continue;
                            }
                            f.setDouble(obj, 0.0);
                            continue;
                        }
                        if (type == Integer.TYPE) {
                            if (!name.contains("health") && !name.contains("hp") && !name.contains("life")) continue;
                            f.setInt(obj, 0);
                            continue;
                        }
                        if (type.isPrimitive() || KillEnforcer.isSafeType(type)) continue;
                        f.set(obj, null);
                    }
                    catch (Throwable throwable) {
                    }
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void nullifyWorldReferences(Object obj) {
        if (obj instanceof Entity) {
            return;
        }
        try {
            for (Class<?> clazz = obj.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        Class<?> type = f.getType();
                        boolean isTarget = false;
                        String kind = null;
                        if (Level.class.isAssignableFrom(type)) {
                            isTarget = true;
                            kind = "Level";
                        }
                        if (!isTarget) continue;
                        boolean written = false;
                        try {
                            f.setAccessible(true);
                            f.set(obj, null);
                            written = true;
                        }
                        catch (Throwable e) {
                            try {
                                Object unsafeObj = KillEnforcer.getUnsafe();
                                if (unsafeObj instanceof Unsafe) {
                                    Unsafe unsafe = (Unsafe)unsafeObj;
                                    long offset = unsafe.objectFieldOffset(f);
                                    unsafe.putObject(obj, offset, null);
                                    written = true;
                                }
                            }
                            catch (Throwable throwable) {
                            }
                        }
                        if (!written) continue;
                    }
                    catch (Throwable throwable) {
                    }
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void removeFromLevelCollections(Entity target, ServerLevel level) {
        try {
            for (Class<?> clazz = level.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(level);
                        if (val == null) continue;
                        if (val instanceof List) {
                            List list = (List)val;
                            try {
                                list.remove(target);
                            }
                            catch (Throwable throwable) {
                            }
                            KillEnforcer.forceRemoveFromList(list, target);
                            continue;
                        }
                        if (val instanceof Set) {
                            Set set = (Set)val;
                            try {
                                set.remove(target);
                            }
                            catch (Throwable throwable) {
                            }
                            KillEnforcer.forceRemoveFromSet(set, target);
                            continue;
                        }
                        if (!(val instanceof Map)) continue;
                        Map map = (Map)val;
                        try {
                            map.remove(target);
                            map.remove(target.getUUID());
                            map.remove(target.getId());
                        }
                        catch (Throwable throwable) {
                        }
                        KillEnforcer.forceRemoveValueFromMap(map, target);
                    }
                    catch (Throwable throwable) {
                    }
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void removeFromStaticCollections(Object target, Class<?> searchClass) {
        for (Class<?> clazz = searchClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val == null) continue;
                    if (val instanceof List) {
                        List list = (List)val;
                        int sizeBefore = list.size();
                        try {
                            list.remove(target);
                        }
                        catch (Throwable throwable) {
                        }
                        KillEnforcer.forceRemoveFromList(list, target);
                        int sizeAfter = list.size();
                        if (sizeBefore != sizeAfter) {
                            System.out.println("[LAL] Removed from static list: " + clazz.getSimpleName() + "." + f.getName() + " size " + sizeBefore + "->" + sizeAfter);
                        }
                        if (!KillEnforcer.listContainsViaUnsafe(list, target)) continue;
                        System.out.println("[LAL] forceReplace static list: " + clazz.getSimpleName() + "." + f.getName());
                        KillEnforcer.forceReplaceStaticField(f, new ArrayList());
                        continue;
                    }
                    if (val instanceof Set) {
                        Set set = (Set)val;
                        try {
                            set.remove(target);
                        }
                        catch (Throwable throwable) {
                        }
                        KillEnforcer.forceRemoveFromSet(set, target);
                        continue;
                    }
                    if (val instanceof Map) {
                        Map map = (Map)val;
                        try {
                            map.remove(target);
                        }
                        catch (Throwable throwable) {
                        }
                        KillEnforcer.forceRemoveFromMap(map, target);
                        KillEnforcer.forceRemoveValueFromMap(map, target);
                        continue;
                    }
                    if (val.getClass().isPrimitive() || val.getClass().getName().startsWith("java.") || val.getClass().getName().startsWith("net.minecraft.")) continue;
                    KillEnforcer.scanInstanceFieldsForTarget(val, target);
                }
                catch (Throwable throwable) {
                }
            }
        }
    }

    private static void markAsRemoved(Object obj) {
        if (obj instanceof Entity) {
            return;
        }
        try {
            for (Class<?> clazz = obj.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                    String name;
                    if (Modifier.isStatic(f.getModifiers()) || f.getType() != Boolean.TYPE || !(name = f.getName().toLowerCase()).contains("removed") && !name.contains("dead") && !name.contains("killed") && !name.contains("destroy") && !name.contains("invalid") && !name.contains("disposed")) continue;
                    try {
                        f.setAccessible(true);
                        f.setBoolean(obj, true);
                    }
                    catch (Throwable e) {
                        try {
                            Object unsafeObj = KillEnforcer.getUnsafe();
                            if (!(unsafeObj instanceof Unsafe)) continue;
                            Unsafe unsafe = (Unsafe)unsafeObj;
                            long offset = unsafe.objectFieldOffset(f);
                            unsafe.putBoolean(obj, offset, true);
                        }
                        catch (Throwable throwable) {
                        }
                    }
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void scanInstanceFieldsForTarget(Object obj, Object target) {
        try {
            for (Class<?> clazz = obj.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val == null) continue;
                        if (val instanceof List) {
                            List list = (List)val;
                            try {
                                list.remove(target);
                            }
                            catch (Throwable throwable) {
                            }
                            KillEnforcer.forceRemoveFromList(list, target);
                            if (!KillEnforcer.listContainsViaUnsafe(list, target)) continue;
                            KillEnforcer.forceReplaceInstanceField(f, obj, new ArrayList());
                            continue;
                        }
                        if (val instanceof Set) {
                            Set set = (Set)val;
                            try {
                                set.remove(target);
                            }
                            catch (Throwable throwable) {
                            }
                            KillEnforcer.forceRemoveFromSet(set, target);
                            continue;
                        }
                        if (!(val instanceof Map)) continue;
                        Map map = (Map)val;
                        try {
                            map.remove(target);
                        }
                        catch (Throwable throwable) {
                        }
                        KillEnforcer.forceRemoveFromMap(map, target);
                        KillEnforcer.forceRemoveValueFromMap(map, target);
                    }
                    catch (Throwable throwable) {
                    }
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void forceZeroListsContaining(Object target, Class<?> searchClass) {
        for (Class<?> clazz = searchClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val == null || !(val instanceof ArrayList) || !KillEnforcer.listContainsViaUnsafe((List)val, target)) continue;
                    KillEnforcer.forceZeroArrayList(val);
                }
                catch (Throwable throwable) {
                }
            }
        }
    }

    private static void forceZeroArrayList(Object list) {
        try {
            Object unsafe = KillEnforcer.getUnsafe();
            if (unsafe == null) {
                return;
            }
            Class<?> uc = unsafe.getClass();
            Method ofo = uc.getMethod("objectFieldOffset", Field.class);
            Method pi = uc.getMethod("putInt", Object.class, Long.TYPE, Integer.TYPE);
            Method po = uc.getMethod("putObject", Object.class, Long.TYPE, Object.class);
            Field sf = ArrayList.class.getDeclaredField("size");
            Field edf = ArrayList.class.getDeclaredField("elementData");
            long sOff = (Long)ofo.invoke(unsafe, sf);
            long dOff = (Long)ofo.invoke(unsafe, edf);
            pi.invoke(unsafe, list, sOff, 0);
            po.invoke(unsafe, list, dOff, new Object[0]);
        }
        catch (Throwable e) {
        }
    }

    private static void deletePersistenceFiles(Entity target) {
        try {
            Level level = target.level();
            if (!(level instanceof ServerLevel)) {
                return;
            }
            ServerLevel sl = (ServerLevel)level;
            MinecraftServer server = sl.getServer();
            if (server == null) {
                return;
            }
            Path worldPath = server.getWorldPath(LevelResource.ROOT);
            if (worldPath == null) {
                return;
            }
            String[] extensions = new String[]{".omnimob", ".entity_save", ".mob_data"};
            try (Stream<Path> stream = Files.walk(worldPath, 3, new FileVisitOption[0]);){
                stream.filter(x$0 -> Files.isRegularFile(x$0, new LinkOption[0])).filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    for (String ext : extensions) {
                        if (!name.endsWith(ext)) continue;
                        return true;
                    }
                    return false;
                }).forEach(p -> {
                    try {
                        Files.delete(p);
                    }
                    catch (Throwable e) {
                    }
                });
            }
        }
        catch (Throwable e) {
        }
    }

    private static boolean listContainsViaUnsafe(List<?> list, Object target) {
        try {
            Object unsafe = KillEnforcer.getUnsafe();
            if (unsafe == null) {
                return false;
            }
            Class<?> uc = unsafe.getClass();
            Method ofo = uc.getMethod("objectFieldOffset", Field.class);
            Method go = uc.getMethod("getObject", Object.class, Long.TYPE);
            Method gi = uc.getMethod("getInt", Object.class, Long.TYPE);
            Field edf = ArrayList.class.getDeclaredField("elementData");
            Field sf = ArrayList.class.getDeclaredField("size");
            long dOff = (Long)ofo.invoke(unsafe, edf);
            long sOff = (Long)ofo.invoke(unsafe, sf);
            Object[] data = (Object[])go.invoke(unsafe, list, dOff);
            int size = (Integer)gi.invoke(unsafe, list, sOff);
            if (data == null) {
                return false;
            }
            for (int i = 0; i < size; ++i) {
                if (data[i] != target && (data[i] == null || !data[i].equals(target))) continue;
                return true;
            }
        }
        catch (Throwable throwable) {
        }
        return false;
    }

    private static void forceReplaceStaticField(Field f, Object newValue) {
        try {
            Object unsafe = KillEnforcer.getUnsafe();
            if (unsafe == null) {
                return;
            }
            Class<?> uc = unsafe.getClass();
            Object base = uc.getMethod("staticFieldBase", Field.class).invoke(unsafe, f);
            long offset = (Long)uc.getMethod("staticFieldOffset", Field.class).invoke(unsafe, f);
            uc.getMethod("putObject", Object.class, Long.TYPE, Object.class).invoke(unsafe, base, offset, newValue);
        }
        catch (Throwable e) {
        }
    }

    private static void forceReplaceInstanceField(Field f, Object owner, Object newValue) {
        try {
            Object unsafe = KillEnforcer.getUnsafe();
            if (unsafe == null) {
                return;
            }
            Class<?> uc = unsafe.getClass();
            long offset = (Long)uc.getMethod("objectFieldOffset", Field.class).invoke(unsafe, f);
            uc.getMethod("putObject", Object.class, Long.TYPE, Object.class).invoke(unsafe, owner, offset, newValue);
        }
        catch (Throwable e) {
        }
    }

    private static void purgeStaticMaps(Entity target) {
        UUID uuid = target.getUUID();
        int entityId = target.getId();
        Class<?> entityClass = target.getClass();
        String entityPackage = entityClass.getPackageName();
        for (Class<?> clazz = entityClass; clazz != null && clazz != LivingEntity.class && clazz != Entity.class; clazz = clazz.getSuperclass()) {
            KillEnforcer.purgeStaticMapsInClass(clazz, target, uuid, entityId);
            for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                Class<?> ft = f.getType();
                if (ft.getPackageName().startsWith("net.minecraft.") || ft.getPackageName().startsWith("java.") || ft.isPrimitive()) continue;
                KillEnforcer.purgeStaticMapsInClass(ft, target, uuid, entityId);
            }
        }
        if (!entityPackage.startsWith("net.minecraft.")) {
            String modPackagePrefix = KillEnforcer.getModPackagePrefix(entityPackage);
            Set<Class<?>> allModClasses = KillEnforcer.collectModClasses(modPackagePrefix, entityClass);
            for (Class<?> modClass : allModClasses) {
                KillEnforcer.purgeStaticMapsInClass(modClass, target, uuid, entityId);
            }
        }
    }

    private static void purgeStaticMapsInClass(Class<?> clazz, Entity target, UUID uuid, int entityId) {
        try {
            for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    Collection col;
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val == null) continue;
                    if (val instanceof Map) {
                        Map map = (Map)val;
                        KillEnforcer.safeMapRemove(map, target);
                        KillEnforcer.safeMapRemove(map, uuid);
                        KillEnforcer.safeMapRemove(map, entityId);
                        KillEnforcer.forceRemoveValueFromMap(map, target);
                    }
                    if (!(val instanceof Collection) || !(col = (Collection)val).contains(target)) continue;
                    col.remove(target);
                    if (!col.contains(target)) continue;
                    KillEnforcer.forceRemoveFromCollection(col, target);
                }
                catch (Throwable throwable) {
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void safeMapRemove(Map<?, ?> map, Object key) {
        try {
            if (map.containsKey(key)) {
                map.remove(key);
                if (map.containsKey(key)) {
                    KillEnforcer.forceRemoveFromMap(map, key);
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void nullifyModFields(Entity target) {
        try {
            for (Class<?> clazz = target.getClass(); clazz != null && clazz != LivingEntity.class && clazz != Entity.class; clazz = clazz.getSuperclass()) {
                for (Field f : KillEnforcer.safeGetDeclaredFields(clazz)) {
                    if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers()) || f.getType().isPrimitive() || KillEnforcer.isSafeType(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        f.set(target, null);
                    }
                    catch (Throwable throwable) {
                    }
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static boolean isSafeType(Class<?> type) {
        String name = type.getName();
        if (type.isEnum()) {
            return true;
        }
        if (type.isArray()) {
            return true;
        }
        if (name.startsWith("net.minecraft.")) {
            return true;
        }
        if (name.startsWith("java.") || name.startsWith("javax.")) {
            return true;
        }
        if (name.startsWith("net.minecraftforge.")) {
            return true;
        }
        if (name.startsWith("com.google.") || name.startsWith("com.mojang.")) {
            return true;
        }
        if (name.startsWith("org.joml.") || name.startsWith("it.unimi.")) {
            return true;
        }
        return Entity.class.isAssignableFrom(type);
    }

    private static void clearBossBarFields(Object obj) {
        try {
            for (Field f : KillEnforcer.safeGetDeclaredFields(obj.getClass())) {
                try {
                    if (!f.getType().getSimpleName().contains("BossEvent") && !f.getType().getSimpleName().contains("BossBar")) continue;
                    f.setAccessible(true);
                    Object bossEvent = f.get(obj);
                    if (!(bossEvent instanceof ServerBossEvent)) continue;
                    ServerBossEvent sbe = (ServerBossEvent)bossEvent;
                    sbe.removeAllPlayers();
                    sbe.setVisible(false);
                }
                catch (Throwable throwable) {
                }
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void forceRemoveFromList(List<?> list, Object target) {
        if (KillEnforcer.forceRemoveFromListReflection(list, target)) {
            return;
        }
        KillEnforcer.forceRemoveFromListUnsafe(list, target);
    }

    private static boolean forceRemoveFromListReflection(List<?> list, Object target) {
        try {
            Field elementDataField = ArrayList.class.getDeclaredField("elementData");
            elementDataField.setAccessible(true);
            Field sizeField = ArrayList.class.getDeclaredField("size");
            sizeField.setAccessible(true);
            Object[] data = (Object[])elementDataField.get(list);
            int size = sizeField.getInt(list);
            if (data == null) {
                return false;
            }
            boolean removed = false;
            for (int i = size - 1; i >= 0; --i) {
                if (data[i] != target && (data[i] == null || !data[i].equals(target))) continue;
                System.arraycopy(data, i + 1, data, i, size - i - 1);
                data[size - 1] = null;
                sizeField.setInt(list, --size);
                removed = true;
            }
            if (removed) {
                try {
                    Field modCountField = AbstractList.class.getDeclaredField("modCount");
                    modCountField.setAccessible(true);
                    modCountField.setInt(list, modCountField.getInt(list) + 1);
                }
                catch (Throwable throwable) {
                }
            }
            return removed;
        }
        catch (Throwable e) {
            return false;
        }
    }

    private static void forceRemoveFromListUnsafe(List<?> list, Object target) {
        try {
            Object unsafe = KillEnforcer.getUnsafe();
            if (unsafe == null) {
                return;
            }
            Class<?> unsafeClass = unsafe.getClass();
            Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            Method getObjectMethod = unsafeClass.getMethod("getObject", Object.class, Long.TYPE);
            Method getIntMethod = unsafeClass.getMethod("getInt", Object.class, Long.TYPE);
            Method putIntMethod = unsafeClass.getMethod("putInt", Object.class, Long.TYPE, Integer.TYPE);
            Method putObjectMethod = unsafeClass.getMethod("putObject", Object.class, Long.TYPE, Object.class);
            Field elementDataField = ArrayList.class.getDeclaredField("elementData");
            Field sizeField = ArrayList.class.getDeclaredField("size");
            long dataOffset = (Long)objectFieldOffset.invoke(unsafe, elementDataField);
            long sizeOffset = (Long)objectFieldOffset.invoke(unsafe, sizeField);
            Object[] data = (Object[])getObjectMethod.invoke(unsafe, list, dataOffset);
            int size = (Integer)getIntMethod.invoke(unsafe, list, sizeOffset);
            if (data == null || size <= 0) {
                return;
            }
            boolean removed = false;
            for (int i = size - 1; i >= 0; --i) {
                if (data[i] != target && (data[i] == null || !data[i].equals(target))) continue;
                System.arraycopy(data, i + 1, data, i, size - i - 1);
                data[size - 1] = null;
                putIntMethod.invoke(unsafe, list, sizeOffset, --size);
                removed = true;
            }
            if (removed) {
                try {
                    Field modCountField = AbstractList.class.getDeclaredField("modCount");
                    long mcOffset = (Long)objectFieldOffset.invoke(unsafe, modCountField);
                    int mc = (Integer)getIntMethod.invoke(unsafe, list, mcOffset);
                    putIntMethod.invoke(unsafe, list, mcOffset, mc + 1);
                }
                catch (Throwable throwable) {}
            }
        }
        catch (Throwable e) {
        }
    }

    private static void forceRemoveFromSet(Set<?> set, Object target) {
        try {
            for (Field f : KillEnforcer.safeGetDeclaredFields(set.getClass())) {
                f.setAccessible(true);
                Object val = f.get(set);
                if (!(val instanceof Map)) continue;
                Map backingMap = (Map)val;
                KillEnforcer.forceRemoveFromMap(backingMap, target);
                return;
            }
            KillEnforcer.forceRemoveFromCollection(set, target);
        }
        catch (Throwable throwable) {
        }
    }

    private static void forceRemoveFromMap(Map<?, ?> map, Object key) {
        try {
            Field tableField = KillEnforcer.findAccessibleField(map.getClass(), "table");
            if (tableField == null && (tableField = KillEnforcer.findAccessibleField(HashMap.class, "table")) == null) {
                tableField = KillEnforcer.findAccessibleField(WeakHashMap.class, "table");
            }
            if (tableField == null) {
                return;
            }
            Object[] table = (Object[])tableField.get(map);
            if (table == null) {
                return;
            }
            Field sizeField = KillEnforcer.findAccessibleField(map.getClass(), "size");
            if (sizeField == null) {
                sizeField = KillEnforcer.findAccessibleField(HashMap.class, "size");
            }
            block2: for (int i = 0; i < table.length; ++i) {
                Object node = table[i];
                Object prev = null;
                while (node != null) {
                    Field keyField = KillEnforcer.findAccessibleField(node.getClass(), "key");
                    Field nextField = KillEnforcer.findAccessibleField(node.getClass(), "next");
                    if (keyField == null || nextField == null) continue block2;
                    Object nodeKey = keyField.get(node);
                    Object next = nextField.get(node);
                    if (nodeKey == key || nodeKey != null && nodeKey.equals(key)) {
                        if (prev == null) {
                            table[i] = next;
                        } else {
                            nextField.set(prev, next);
                        }
                        if (sizeField != null) {
                            sizeField.setInt(map, sizeField.getInt(map) - 1);
                        }
                        return;
                    }
                    prev = node;
                    node = next;
                }
            }
        }
        catch (Throwable e) {
        }
    }

    private static void forceRemoveValueFromMap(Map<?, ?> map, Object value) {
        try {
            Iterator<?> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Object rawEntry = it.next();
                if (!(rawEntry instanceof Map.Entry)) continue;
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>)rawEntry;
                if (entry.getValue() != value) continue;
                try {
                    it.remove();
                }
                catch (Throwable e) {
                    KillEnforcer.forceRemoveFromMap(map, entry.getKey());
                }
                return;
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void forceRemoveFromCollection(Collection<?> col, Object target) {
        try {
            Iterator<?> it = col.iterator();
            while (it.hasNext()) {
                if (it.next() != target) continue;
                it.remove();
                return;
            }
        }
        catch (Throwable throwable) {
        }
    }

    public static void restoreEventBusIfNeeded() {
        KillEnforcer.restoreEventBus();
    }

    private static void restoreEventBus() {
        try {
            IEventBus restorationTarget;
            IEventBus currentBus = MinecraftForge.EVENT_BUS;
            String busClassName = currentBus.getClass().getName();
            if (busClassName.startsWith("net.minecraftforge.")) {
                return;
            }
            String capturedClassName = CAPTURED_EVENT_BUS.getClass().getName();
            if (capturedClassName.startsWith("net.minecraftforge.")) {
                restorationTarget = CAPTURED_EVENT_BUS;
            } else {
                try {
                    restorationTarget = BusBuilder.builder().build();
                    for (Class<?> ebClass = restorationTarget.getClass(); ebClass != null && ebClass != Object.class; ebClass = ebClass.getSuperclass()) {
                        for (Field f : KillEnforcer.safeGetDeclaredFields(ebClass)) {
                            if (Modifier.isStatic(f.getModifiers())) continue;
                            try {
                                f.setAccessible(true);
                                f.set(restorationTarget, f.get(currentBus));
                            }
                            catch (Throwable throwable) {
                            }
                        }
                    }
                }
                catch (Throwable e) {
                    return;
                }
            }
            Field eventBusField = MinecraftForge.class.getDeclaredField("EVENT_BUS");
            try {
                Object unsafe = KillEnforcer.getUnsafe();
                if (unsafe != null) {
                    Object base = unsafe.getClass().getMethod("staticFieldBase", Field.class).invoke(unsafe, eventBusField);
                    long offset = (Long)unsafe.getClass().getMethod("staticFieldOffset", Field.class).invoke(unsafe, eventBusField);
                    unsafe.getClass().getMethod("putObject", Object.class, Long.TYPE, Object.class).invoke(unsafe, base, offset, restorationTarget);
                    return;
                }
            }
            catch (Throwable throwable) {
            }
            eventBusField.setAccessible(true);
            eventBusField.set(null, restorationTarget);
        }
        catch (Throwable e) {
        }
    }

    private static Object getUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return f.get(null);
        }
        catch (Throwable e) {
            return null;
        }
    }

    private static final Map<Class<?>, Field[]> FIELDS_CACHE = new ConcurrentHashMap<>();
    private static final Field[] EMPTY_FIELDS = new Field[0];

    private static Field[] safeGetDeclaredFields(Class<?> clazz) {
        return FIELDS_CACHE.computeIfAbsent(clazz, c -> {
            try {
                return c.getDeclaredFields();
            }
            catch (Throwable t) {
                return EMPTY_FIELDS;
            }
        });
    }

    static {
        block15: {
            try {
                MethodHandles.Lookup livingLookup = MethodHandles.privateLookupIn(LivingEntity.class, MethodHandles.lookup());
                HEALTH_HANDLE = FieldAccessUtil.findVarHandle(livingLookup, LivingEntity.class, Float.TYPE, "f_20958_", "health");
                DEATH_TIME_HANDLE = FieldAccessUtil.findVarHandle(livingLookup, LivingEntity.class, Integer.TYPE, "f_20962_", "deathTime");
                DEAD_HANDLE = FieldAccessUtil.findVarHandle(livingLookup, LivingEntity.class, Boolean.TYPE, "f_20960_", "dead");
                MethodHandles.Lookup entityLookup = MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup());
                REMOVAL_REASON_HANDLE = FieldAccessUtil.findVarHandle(entityLookup, Entity.class, Entity.RemovalReason.class, "f_146801_", "removalReason");
                if (HEALTH_HANDLE == null && (HEALTH_FIELD = KillEnforcer.findFieldByType(LivingEntity.class, Float.TYPE, "f_20958_", "health")) != null) {
                }
                if (DEATH_TIME_HANDLE == null && (DEATH_TIME_FIELD = KillEnforcer.findFieldByType(LivingEntity.class, Integer.TYPE, "f_20962_", "deathTime")) != null) {
                }
                if (DEAD_HANDLE == null && (DEAD_FIELD = KillEnforcer.findFieldByType(LivingEntity.class, Boolean.TYPE, "f_20960_", "dead")) != null) {
                }
                if (REMOVAL_REASON_HANDLE == null && (REMOVAL_REASON_FIELD = KillEnforcer.findFieldByType(Entity.class, Entity.RemovalReason.class, "f_146801_", "removalReason")) != null) {
                }
                DROP_ALL_DEATH_LOOT = KillEnforcer.findMethod(LivingEntity.class, new String[]{"m_6668_", "dropAllDeathLoot"}, DamageSource.class);
                GET_EXPERIENCE_REWARD = KillEnforcer.findMethod(LivingEntity.class, new String[]{"m_6552_", "getExperienceReward"}, new Class[0]);
                try {
                    MethodHandles.Lookup specialLookup = MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup());
                    ENTITY_SET_REMOVED = specialLookup.findSpecial(Entity.class, "setRemoved", MethodType.methodType(Void.TYPE, Entity.RemovalReason.class), Entity.class);
                    if (ENTITY_SET_REMOVED == null) {
                        ENTITY_SET_REMOVED = specialLookup.findSpecial(Entity.class, "m_146870_", MethodType.methodType(Void.TYPE, Entity.RemovalReason.class), Entity.class);
                    }
                }
                catch (Throwable e) {
                }
                for (String name : new String[]{"f_135354_", "itemsById"}) {
                    try {
                        ENTITY_DATA_ITEMS_BY_ID = SynchedEntityData.class.getDeclaredField(name);
                        ENTITY_DATA_ITEMS_BY_ID.setAccessible(true);
                        break;
                    }
                    catch (NoSuchFieldException noSuchFieldException) {
                    }
                }
                if (ENTITY_DATA_ITEMS_BY_ID != null) break block15;
                for (Field f : KillEnforcer.safeGetDeclaredFields(SynchedEntityData.class)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (ft.isArray() && ft.getComponentType().getSimpleName().contains("DataItem")) {
                        f.setAccessible(true);
                        ENTITY_DATA_ITEMS_BY_ID = f;
                    } else {
                        if (!ft.getName().contains("Int2Object")) continue;
                        f.setAccessible(true);
                        ENTITY_DATA_ITEMS_BY_ID = f;
                    }
                    break;
                }
            }
            catch (Throwable e) {
            }
        }
        CAPTURED_EVENT_BUS = MinecraftForge.EVENT_BUS;
    }
}

