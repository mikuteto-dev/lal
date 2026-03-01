package jp.mikumiku.lal.enforcement;

import jp.mikumiku.lal.util.FieldAccessUtil;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraftforge.entity.PartEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LALEntityRemover {

    private static volatile boolean init = false;
    private static Field srvEntityManager;
    private static Field srvEntityTickList;
    private static Field tickListActive;
    private static Field tickListPassive;
    private static Field mgrSectionStorage;
    private static Field mgrVisibleStorage;
    private static Field mgrKnownUuids;
    private static Field lookupByUuid;
    private static Field lookupById;

    private static Method tickListEnsureNotIterated;
    private static Method sectionStorageGetSection;
    private static Method mgrUpdateSectionStatus;
    private static Method chunkSourceRemoveEntity;

    private static Field resolveField(Class<?> clazz, String... names) {
        for (String name : names) {
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
        }
        return null;
    }

    private static Object get(Field f, Object obj) {
        if (f == null) return null;
        try { return f.get(obj); } catch (Throwable t) { return null; }
    }

    private static Method resolveMethod(Object target, String[] names, Class<?>... paramTypes) {
        if (target == null) return null;
        for (String name : names) {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name, paramTypes);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return null;
    }

    private static Method resolveMethodByNameAndParamCount(Object target, String[] names, int paramCount) {
        if (target == null) return null;
        for (String name : names) {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
        }
        return null;
    }

    private static void ensureInit(ServerLevel level) {
        if (init) return;
        init = true;
        try {
            srvEntityManager = resolveField(level.getClass(), "f_143244_", "entityManager");
            srvEntityTickList = resolveField(level.getClass(), "f_143243_", "entityTickList");

            Object mgr = get(srvEntityManager, level);
            if (mgr != null) {
                mgrSectionStorage = resolveField(mgr.getClass(), "f_157495_", "sectionStorage");
                mgrVisibleStorage = resolveField(mgr.getClass(), "f_157494_", "visibleEntityStorage");
                mgrKnownUuids = resolveField(mgr.getClass(), "f_157491_", "knownUuids");

                Object vis = get(mgrVisibleStorage, mgr);
                if (vis != null) {
                    lookupByUuid = resolveField(vis.getClass(), "f_156808_", "byUuid");
                    lookupById = resolveField(vis.getClass(), "f_156807_", "byId");
                }

                Object storage = get(mgrSectionStorage, mgr);
                if (storage != null) {
                    sectionStorageGetSection = resolveMethod(storage,
                            new String[]{"m_156895_", "getSection"}, Long.TYPE);
                }

                mgrUpdateSectionStatus = resolveMethodByNameAndParamCount(mgr,
                        new String[]{"m_157509_", "updateSectionStatus"}, 2);
            }

            Object tl = get(srvEntityTickList, level);
            if (tl != null) {
                tickListActive = resolveField(tl.getClass(), "f_156903_", "active");
                tickListPassive = resolveField(tl.getClass(), "f_156904_", "passive");
                tickListEnsureNotIterated = resolveMethod(tl,
                        new String[]{"m_156907_", "ensureActiveIsNotIterated"});
            }

            try {
                Object cs = level.getChunkSource();
                chunkSourceRemoveEntity = resolveMethod(cs,
                        new String[]{"m_83420_", "removeEntity"}, Entity.class);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void deleteFromLevel(Entity entity, ServerLevel level) {
        if (entity == null || level == null) return;
        ensureInit(level);

        try {
            if (FieldAccessUtil.REMOVAL_REASON != null) {
                Object current = FieldAccessUtil.REMOVAL_REASON.get(entity);
                if (current == null) {
                    FieldAccessUtil.REMOVAL_REASON.set(entity, Entity.RemovalReason.DISCARDED);
                }
            }
        } catch (Throwable ignored) {}

        try {
            for (Entity p : new ArrayList<>(entity.getPassengers()))
                deleteFromLevel(p, level);
        } catch (Throwable ignored) {}

        deleteFromPersistentManager(entity, level);
    }

    private static void deleteFromPersistentManager(Entity entity, ServerLevel level) {
        int id = entity.getId();
        UUID uuid = entity.getUUID();

        Object mgr = get(srvEntityManager, level);
        if (mgr == null) return;

        Object section = null;
        long sectionKey = 0;
        try {
            Object storage = get(mgrSectionStorage, mgr);
            if (storage != null && sectionStorageGetSection != null) {
                sectionKey = SectionPos.asLong(entity.blockPosition());
                section = sectionStorageGetSection.invoke(storage, sectionKey);
                if (section != null) {
                    removeEntityFromSection(section, entity);
                }
            }
        } catch (Throwable ignored) {}

        try {
            Object tl = get(srvEntityTickList, level);
            if (tl != null) {
                if (tickListEnsureNotIterated != null) {
                    try { tickListEnsureNotIterated.invoke(tl); } catch (Throwable ignored) {}
                }
                Object active = get(tickListActive, tl);
                if (active != null) {
                    try { active.getClass().getMethod("remove", Integer.TYPE).invoke(active, id); }
                    catch (Throwable ignored) {}
                }
                Object passive = get(tickListPassive, tl);
                if (passive != null) {
                    try { passive.getClass().getMethod("remove", Integer.TYPE).invoke(passive, id); }
                    catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        chunkMapRemoveEntity(entity, level, id);

        if (entity instanceof Mob m) {
            try { level.navigatingMobs.remove(m); } catch (Throwable ignored) {}
        }

        if (entity.isMultipartEntity()) {
            PartEntity<?>[] parts = entity.getParts();
            if (parts != null) {
                for (PartEntity<?> p : parts) {
                    try { level.dragonParts.remove(p.getId()); } catch (Throwable ignored) {}
                }
            }
        }

        try {
            entity.updateDynamicGameEventListener(DynamicGameEventListener::remove);
        } catch (Throwable ignored) {}

        try { entity.onRemovedFromWorld(); } catch (Throwable ignored) {}

        try {
            Object vis = get(mgrVisibleStorage, mgr);
            if (vis != null) {
                Object byUuid = get(lookupByUuid, vis);
                if (byUuid instanceof Map) ((Map<?, ?>) byUuid).remove(uuid);
                Object byId = get(lookupById, vis);
                if (byId != null) {
                    try { byId.getClass().getMethod("remove", Integer.TYPE).invoke(byId, id); }
                    catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (chunkSourceRemoveEntity != null) {
                chunkSourceRemoveEntity.invoke(level.getChunkSource(), entity);
            }
        } catch (Throwable ignored) {}

        try {
            Object knownUuids = get(mgrKnownUuids, mgr);
            if (knownUuids instanceof Set) ((Set<?>) knownUuids).remove(uuid);
        } catch (Throwable ignored) {}

        try { entity.levelCallback = EntityInLevelCallback.NULL; } catch (Throwable ignored) {}

        try {
            if (mgrUpdateSectionStatus != null && section != null) {
                mgrUpdateSectionStatus.invoke(mgr, sectionKey, section);
            }
        } catch (Throwable ignored) {}

        try { level.getScoreboard().entityRemoved(entity); } catch (Throwable ignored) {}
    }

    private static void removeEntityFromSection(Object section, Entity entity) {
        for (Class<?> c = section.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : FieldAccessUtil.safeGetDeclaredFields(c)) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(section);
                    if (val == null) continue;
                    if (!val.getClass().getSimpleName().contains("ClassInstanceMultiMap")) continue;
                    removeFromClassInstanceMultiMapDirect(val, entity);
                    return;
                } catch (Throwable ignored) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeFromClassInstanceMultiMapDirect(Object multiMap, Entity entity) {
        try {
            Field byClassField = resolveField(multiMap.getClass(), "f_13527_", "byClass");
            if (byClassField != null) {
                Object byClass = byClassField.get(multiMap);
                if (byClass instanceof Map) {
                    for (Object list : ((Map<?, ?>) byClass).values()) {
                        if (list instanceof java.util.List) {
                            try { ((java.util.List<?>) list).remove(entity); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        try {
            Field allInstancesField = resolveField(multiMap.getClass(), "f_13525_", "allInstances");
            if (allInstancesField != null) {
                Object allInstances = allInstancesField.get(multiMap);
                if (allInstances instanceof java.util.List) {
                    ((java.util.List<?>) allInstances).remove(entity);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void chunkMapRemoveEntity(Entity entity, ServerLevel level, int id) {
        try {
            Object chunkMap = level.getChunkSource().chunkMap;
            Field entityMapField = resolveField(chunkMap.getClass(), "f_140150_", "entityMap");
            if (entityMapField == null) return;
            Object entityMap = entityMapField.get(chunkMap);
            if (entityMap == null) return;
            Object tracked;
            try {
                tracked = entityMap.getClass().getMethod("remove", Integer.TYPE).invoke(entityMap, id);
            } catch (Throwable t) {
                return;
            }
            if (tracked != null) {
                broadcastRemove(tracked, id);
            }
        } catch (Throwable ignored) {}
    }

    private static void broadcastRemove(Object tracked, int entityId) {
        try {
            Field seenByField = resolveField(tracked.getClass(), "f_140475_", "seenBy");
            if (seenByField == null) {
                seenByField = FieldAccessUtil.findAccessibleField(tracked.getClass(), "seenBy");
            }
            if (seenByField == null) return;
            Object sb = seenByField.get(tracked);
            if (!(sb instanceof Set)) return;
            ClientboundRemoveEntitiesPacket pkt = new ClientboundRemoveEntitiesPacket(new int[]{entityId});
            for (Object conn : (Set<?>) sb) {
                if (!(conn instanceof ServerPlayerConnection)) continue;
                try { ((ServerPlayerConnection) conn).getPlayer().connection.send(pkt); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}
