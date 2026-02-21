package jp.mikumiku.lal.mixin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.core.EntityLedger;
import jp.mikumiku.lal.core.EntityLedgerEntry;
import jp.mikumiku.lal.core.KillSavedData;
import jp.mikumiku.lal.enforcement.EnforcementDaemon;
import jp.mikumiku.lal.enforcement.ImmortalEnforcer;
import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.enforcement.RegistryCleaner;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={ServerLevel.class}, priority=0x7FFFFFFF)
public abstract class ServerLevelMixin {
    private static final AABB EMPTY_AABB = new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    private static final Set<UUID> GHOST_CLEANUP = ConcurrentHashMap.newKeySet();
    private static volatile boolean lal$killDataRestored = false;
    private static final ConcurrentHashMap<UUID, Integer> GHOST_RETRY_COUNT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> DEAD_RETRY_COUNT = new ConcurrentHashMap<>();
    private static final int MAX_DELETION_RETRIES = 2048;

    public ServerLevelMixin() {
        super();
    }

    @Inject(method={"tick"}, at={@At(value="HEAD")})
    private void lal$onTickStart(CallbackInfo ci) {
        EntityMethodHooks.mixinTickRan = true;
        LivingEntity living;
        Entity entity;
        ServerLevel level = (ServerLevel)(Object)this;
        int repairsThisTick = 0;
        int maxRepairsPerTick = 20;
        try {
            EnforcementDaemon.ensureRunning();
        }
        catch (Exception exception) {
        }
        try {
            KillEnforcer.restoreEventBusIfNeeded();
        }
        catch (Exception exception) {
        }
        int currentTick = level.getServer().getTickCount();
        if (currentTick < 5) {
            lal$killDataRestored = false;
        }
        if (!lal$killDataRestored) {
            lal$killDataRestored = true;
            try {
                KillSavedData data = KillSavedData.get(level);
                for (UUID uuid : data.getKilledUuids()) {
                    if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) continue;
                    CombatRegistry.addToKillSet(uuid);
                    CombatRegistry.setForcedHealth(uuid, 0.0f);
                    CombatRegistry.markDroppedLoot(uuid);
                }
            }
            catch (Exception exception) {
                }
        }
        for (UUID uuid : CombatRegistry.getImmortalSet()) {
            if (repairsThisTick >= maxRepairsPerTick) break;
            entity = level.getEntity(uuid);
            if (!(entity instanceof LivingEntity)) continue;
            living = (LivingEntity)entity;
            ImmortalEnforcer.enforceImmortality(living);
            ++repairsThisTick;
        }
        for (UUID uuid : CombatRegistry.getKillSet()) {
            entity = level.getEntity(uuid);
            if (!(entity instanceof LivingEntity)) continue;
            living = (LivingEntity)entity;
            KillEnforcer.enforceDeathState(living);
            try {
                living.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
            }
            catch (Exception exception) {
                }
            CombatRegistry.setForcedHealth(uuid, 0.0f);
        }
        for (ServerPlayer player : level.players()) {
            UUID playerUuid = player.getUUID();
            boolean hasEquipment = LALSwordItem.hasLALEquipment((Player)player);
            boolean isInKillSet = CombatRegistry.isInKillSet(playerUuid);
            if (hasEquipment && !isInKillSet) {
                if (CombatRegistry.isInImmortalSet(playerUuid)) continue;
                CombatRegistry.addToImmortalSet(playerUuid);
                continue;
            }
            if (hasEquipment || isInKillSet || !CombatRegistry.isInImmortalSet(playerUuid)) continue;
            CombatRegistry.removeFromImmortalSet(playerUuid);
            CombatRegistry.clearForcedHealth(playerUuid);
        }

        if (EntityMethodHooks.tryRunForcedTick(currentTick)) {
            EntityMethodHooks.forcedTickThisTick.clear();
            for (ServerPlayer fp : level.players()) {
                try {
                    if (!LALSwordItem.hasLALEquipment((Player) fp)) continue;
                    java.util.UUID fpUuid = fp.getUUID();
                    if (EntityMethodHooks.normalTickAttempted.remove(fpUuid) != null) {
                        EntityMethodHooks.normalTickSeen.put(fpUuid, currentTick - 1);
                        continue;
                    }
                    Integer lastNormal = EntityMethodHooks.normalTickSeen.get(fpUuid);
                    if (lastNormal != null && lastNormal < currentTick - 1) {
                        EntityMethodHooks.forcedTickThisTick.put(fpUuid, Boolean.TRUE);
                        EntityMethodHooks.setBypass(true);
                        try {
                            fp.tick();
                        } finally {
                            EntityMethodHooks.setBypass(false);
                        }
                        EntityMethodHooks.lastTickSeen.put(fpUuid, currentTick);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    @Inject(method={"tick"}, at={@At(value="TAIL")})
    private void lal$onTickEnd(CallbackInfo ci) {
        EntityLedgerEntry entry2;
        LivingEntity living;
        Entity entity;
        ServerLevel level = (ServerLevel)(Object)this;
        int tickCount = level.getServer().getTickCount();
        int repairsThisTick = 0;
        int maxRepairsPerTick = 20;
        for (UUID uuid : new ArrayList<UUID>(CombatRegistry.getKillSet())) {
            if (repairsThisTick >= maxRepairsPerTick) break;
            entity = level.getEntity(uuid);
            if (entity instanceof LivingEntity) {
                int ticksInKillSet;
                living = (LivingEntity)entity;
                entry2 = EntityLedger.get().getOrCreate(uuid);
                entry2.lastSeenTick = tickCount;
                Integer killStartTick = CombatRegistry.getKillStartTick(uuid);
                int n = ticksInKillSet = killStartTick != null ? tickCount - killStartTick : 0;
                if (KillEnforcer.verifyKill(living)) {
                    CombatRegistry.recordKill((Entity)living, tickCount, CombatRegistry.getKillAttacker(uuid));
                    KillEnforcer.initiateKill(living, level);
                    KillEnforcer.executeRemoval(living, level);
                    entry2.recordSuccess();
                    CombatRegistry.confirmDead(uuid);
                    GHOST_CLEANUP.add(uuid);
                    ServerLevelMixin.lal$persistKill(level, uuid);
                    continue;
                }
                if (ticksInKillSet >= 65) {
                    CombatRegistry.recordKill((Entity)living, tickCount, CombatRegistry.getKillAttacker(uuid));
                    KillEnforcer.executeKill(living, level);
                    CombatRegistry.confirmDead(uuid);
                    ServerLevelMixin.lal$persistKill(level, uuid);
                    ServerLevelMixin.lal$forceRemoveEntity((Entity)living);
                    ++repairsThisTick;
                    continue;
                }
                entry2.recordFailure();
                KillEnforcer.enforceDeathState(living);
                try {
                    living.getEntityData().set(LivingEntity.DATA_HEALTH_ID, Float.valueOf(0.0f));
                }
                catch (Exception exception) {
                        }
                living.noPhysics = true;
                ++repairsThisTick;
                continue;
            }
            CombatRegistry.confirmDead(uuid);
            GHOST_CLEANUP.remove(uuid);
            ServerLevelMixin.lal$persistKill(level, uuid);
        }
        for (UUID uuid : new ArrayList<UUID>(GHOST_CLEANUP)) {
            Entity ghost = level.getEntity(uuid);
            if (ghost == null) {
                GHOST_CLEANUP.remove(uuid);
                GHOST_RETRY_COUNT.remove(uuid);
                continue;
            }
            int ghostRetries = GHOST_RETRY_COUNT.merge(uuid, 1, Integer::sum);
            if (ghostRetries > MAX_DELETION_RETRIES) {
                try { ghost.noPhysics = true; } catch (Throwable ignored) {}
                continue;
            }
            if (!(ghost instanceof LivingEntity)) continue;
            living = (LivingEntity)ghost;
            KillEnforcer.executeRemoval(living, level);
            RegistryCleaner.deleteFromAllRegistries(ghost, level);
            try {
                ghost.setBoundingBox(EMPTY_AABB);
                ghost.noPhysics = true;
            }
            catch (Exception e) {
            }
            ServerLevelMixin.lal$forceRemoveEntity(ghost);
            ++repairsThisTick;
        }
        for (UUID uuid : new ArrayList<UUID>(CombatRegistry.getDeadConfirmedSet())) {
            entity = level.getEntity(uuid);
            if (entity == null) {
                DEAD_RETRY_COUNT.remove(uuid);
                continue;
            }
            int deadRetries = DEAD_RETRY_COUNT.merge(uuid, 1, Integer::sum);
            if (deadRetries > MAX_DELETION_RETRIES) {
                try { entity.noPhysics = true; } catch (Throwable ignored) {}
                continue;
            }
            RegistryCleaner.deleteFromAllRegistries(entity, level);
            ServerLevelMixin.lal$forceRemoveEntity(entity);
            try {
                entity.setBoundingBox(EMPTY_AABB);
                entity.noPhysics = true;
            }
            catch (Exception e) {}
        }
        CombatRegistry.cleanupKillHistory(tickCount);
        for (UUID uuid : CombatRegistry.getImmortalSet()) {
            if (repairsThisTick >= maxRepairsPerTick) break;
            entity = level.getEntity(uuid);
            if (!(entity instanceof LivingEntity)) continue;
            living = (LivingEntity)entity;
            entry2 = EntityLedger.get().getOrCreate(uuid);
            entry2.lastSeenTick = tickCount;
            ImmortalEnforcer.enforceImmortality(living);
            if (!ImmortalEnforcer.verifyAlive(living)) {
                entry2.recordFailure();
                ++repairsThisTick;
                continue;
            }
            entry2.recordSuccess();
        }
    }

    @Inject(method={"addFreshEntity"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$onAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (CombatRegistry.isDeadConfirmed(entity.getUUID()) || CombatRegistry.isInKillSet(entity.getUUID())) {
            cir.setReturnValue(false);
        }
    }

    private static void lal$persistKill(ServerLevel level, UUID uuid) {
        try {
            KillSavedData data = KillSavedData.get(level);
            data.addKill(uuid);
        }
        catch (Exception exception) {
        }
    }

    private static void lal$forceRemoveEntity(Entity entity) {
        try {
            if (jp.mikumiku.lal.util.FieldAccessUtil.REMOVAL_REASON != null) {
                jp.mikumiku.lal.util.FieldAccessUtil.REMOVAL_REASON.set(entity, Entity.RemovalReason.KILLED);
            } else {
                Field removalField = null;
                try { removalField = Entity.class.getDeclaredField("f_146795_"); }
                catch (NoSuchFieldException e) {
                    try { removalField = Entity.class.getDeclaredField("removalReason"); }
                    catch (NoSuchFieldException e2) {}
                }
                if (removalField != null) {
                    removalField.setAccessible(true);
                    removalField.set(entity, Entity.RemovalReason.KILLED);
                }
            }
            try {
                for (Class<?> clazz = entity.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                    for (Field f : clazz.getDeclaredFields()) {
                        try {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            f.setAccessible(true);
                            Object val = f.get(entity);
                            if (val instanceof EntityInLevelCallback) {
                                try { ((EntityInLevelCallback) val).onRemove(Entity.RemovalReason.KILLED); } catch (Throwable t) {}
                                f.set(entity, EntityInLevelCallback.NULL);
                            }
                        } catch (Throwable t) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
        catch (Exception e) {
        }
    }
}

