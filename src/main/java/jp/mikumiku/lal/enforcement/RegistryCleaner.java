package jp.mikumiku.lal.enforcement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.event.level.LevelEvent;
import jp.mikumiku.lal.util.FieldAccessUtil;
public class RegistryCleaner {
    private static final Map<String, String[]> SRG_NAMES;

    public RegistryCleaner() {
        super();
    }

    public static void deleteFromAllRegistries(Entity target, ServerLevel level) {
        int id;
        UUID uuid;
        block62: {
            block61: {
                uuid = target.getUUID();
                id = target.getId();
                try {
                    target.setRemoved(Entity.RemovalReason.KILLED);
                }
                catch (Throwable throwable) {
                    }
                try {
                    RegistryCleaner.destroyAllCallbacks(target);
                }
                catch (Throwable throwable) {
                    }
                try {
                    EntityInLevelCallback cb = target.levelCallback;
                    if (cb != null) {
                        cb.onRemove(Entity.RemovalReason.KILLED);
                    }
                }
                catch (Throwable cb) {
                    }
                try {
                    Object sectionStorage;
                    long sectionKey = SectionPos.asLong((BlockPos)target.blockPosition());
                    Object entityManager = RegistryCleaner.findField(level, "entityManager", "PersistentEntitySectionManager");
                    if (entityManager == null || (sectionStorage = RegistryCleaner.findField(entityManager, "sectionStorage", "SectionStorage")) == null) break block61;
                    try {
                        Method getSection = sectionStorage.getClass().getMethod("getSection", Long.TYPE);
                        Object section = getSection.invoke(sectionStorage, sectionKey);
                        if (section != null) {
                            RegistryCleaner.removeFromSectionByClass(section, target);
                        }
                    }
                    catch (Throwable getSection) {
                            }
                    RegistryCleaner.removeEntityFromSections(sectionStorage, target);
                }
                catch (Throwable e) {
                    }
            }
            try {
                Object sectionStorage;
                Object entityManager = RegistryCleaner.findField(level, "entityManager", "PersistentEntitySectionManager");
                if (entityManager == null) break block62;
                RegistryCleaner.removeUuidFromSet(entityManager, "knownUuids", uuid);
                Object visibleStorage = RegistryCleaner.findField(entityManager, "visibleEntityStorage", "EntityLookup");
                if (visibleStorage != null) {
                    Object byId;
                    Object byUuid = RegistryCleaner.findField(visibleStorage, "byUuid", "Map");
                    if (byUuid instanceof Map) {
                        Map uuidMap = (Map)byUuid;
                        uuidMap.remove(uuid);
                    }
                    if ((byId = RegistryCleaner.findField(visibleStorage, "byId", "Int2Object")) != null) {
                        try {
                            byId.getClass().getMethod("remove", Integer.TYPE).invoke(byId, id);
                        }
                        catch (Throwable getSection) {
                                    }
                    }
                    RegistryCleaner.removeFromAllMaps(visibleStorage, uuid, id);
                }
                if ((sectionStorage = RegistryCleaner.findField(entityManager, "sectionStorage", "SectionStorage")) != null) {
                    RegistryCleaner.removeEntityFromSections(sectionStorage, target);
                }
            }
            catch (Throwable e) {
            }
        }
        try {
            EntityTickList tickList = level.entityTickList;
            tickList.active.remove(id);
        }
        catch (Throwable e) {
            try {
                Object activeMap;
                Object tickList = RegistryCleaner.findField(level, "entityTickList", "EntityTickList");
                if (tickList != null && (activeMap = RegistryCleaner.findField(tickList, "active", "Int2Object")) != null) {
                    Method removeMethod = activeMap.getClass().getMethod("remove", Integer.TYPE);
                    removeMethod.invoke(activeMap, id);
                }
            }
            catch (Throwable tickList) {
            }
        }
        try {
            Object seenBy;
            Field seenByField;
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            Object tracked = chunkMap.entityMap.remove(id);
            if (tracked != null && (seenByField = FieldAccessUtil.findAccessibleField(tracked.getClass(), "seenBy")) != null && (seenBy = seenByField.get(tracked)) instanceof Set) {
                Set connections = (Set)seenBy;
                ClientboundRemoveEntitiesPacket removePacket = new ClientboundRemoveEntitiesPacket(new int[]{id});
                for (Object conn : connections) {
                    try {
                        if (!(conn instanceof ServerPlayerConnection)) continue;
                        ServerPlayerConnection spc = (ServerPlayerConnection)conn;
                        spc.getPlayer().connection.send((Packet)removePacket);
                    }
                    catch (Throwable throwable) {}
                }
            }
        }
        catch (Throwable e) {
            try {
                ServerChunkCache chunkSource = level.getChunkSource();
                Object chunkMap = RegistryCleaner.findField(chunkSource, "chunkMap", "ChunkMap");
                if (chunkMap != null) {
                    RegistryCleaner.removeFromAllMaps(chunkMap, uuid, id);
                }
            }
            catch (Throwable chunkSource) {
            }
        }
        if (target instanceof Mob) {
            Mob mob = (Mob)target;
            try {
                level.navigatingMobs.remove(mob);
            }
            catch (Throwable e) {
                try {
                    Object navMobs = RegistryCleaner.findField(level, "navigatingMobs", "Set");
                    if (navMobs instanceof Set) {
                        Set set = (Set)navMobs;
                        set.remove(mob);
                    }
                }
                catch (Throwable navMobs) {
                    }
            }
        }
        if (target.isMultipartEntity()) {
            try {
                PartEntity[] parts = target.getParts();
                if (parts != null) {
                    for (PartEntity part : parts) {
                        try {
                            level.dragonParts.remove(part.getId());
                        }
                        catch (Throwable throwable) {
                                    }
                    }
                }
            }
            catch (Throwable parts) {
            }
        }
        try {
            RegistryCleaner.purgeFromAllLevelStructures(target, level);
        }
        catch (Throwable throwable) {
        }
        try {
            target.updateDynamicGameEventListener(DynamicGameEventListener::remove);
        }
        catch (Throwable parts) {
        }
        try {
            level.getScoreboard().entityRemoved(target);
        }
        catch (Throwable parts) {
        }
        try {
            Field validField = FieldAccessUtil.findAccessibleField(target.getClass(), "valid");
            if (validField != null) {
                validField.set(target, false);
            }
        }
        catch (Throwable validField) {
        }
        try {
            target.isAddedToWorld = false;
        }
        catch (Throwable validField) {
        }
        try {
            Object caps;
            Method getCaps = FieldAccessUtil.findAccessibleMethod(target.getClass(), "getCapabilities", new Class[0]);
            if (getCaps != null && (caps = getCaps.invoke((Object)target, new Object[0])) != null) {
                Method invalidate = caps.getClass().getMethod("invalidate", new Class[0]);
                invalidate.invoke(caps, new Object[0]);
            }
        }
        catch (Throwable throwable) {
        }
        try {
            target.levelCallback = EntityInLevelCallback.NULL;
        }
        catch (Throwable throwable) {
        }
        try {
            target.discard();
        }
        catch (Throwable throwable) {
        }
        try {
            Method m = target.getClass().getDeclaredMethod("onLevelUnload", LevelEvent.Unload.class);
            m.setAccessible(true);
            m.invoke(target, new LevelEvent.Unload((LevelAccessor) target.level()));
        } catch (Exception ignored) {}
    }

    private static void removeFromSectionByClass(Object section, Entity target) {
        try {
            RegistryCleaner.removeFromAllStoragesInSection(section, target);
            try {
                Method removeMethod = section.getClass().getMethod("remove", Entity.class);
                removeMethod.invoke(section, target);
            }
            catch (NoSuchMethodException e) {
                try {
                    Method removeMethod = section.getClass().getMethod("remove", Object.class);
                    removeMethod.invoke(section, target);
                }
                catch (Throwable throwable) {}
            }
        }
        catch (Throwable throwable) {
        }
    }

    private static void removeFromAllStoragesInSection(Object section, Entity target) {
        if (section == null) return;

        synchronized (section) {
            for (Class<?> clazz = section.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field f : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(section);
                        if (val == null) continue;
                        String typeName = val.getClass().getSimpleName();
                        if (typeName.contains("ClassInstanceMultiMap")) {
                            RegistryCleaner.removeFromClassInstanceMultiMap(val, target);
                        } else if (val instanceof List) {
                            RegistryCleaner.safeListRemove((List) val, target);
                        } else if (val instanceof Set) {
                            try { ((Set)val).remove(target); } catch (Throwable t) {}
                        }
                    } catch (Throwable throwable) {}
                }
            }
        }
    }

    private static void removeFromClassInstanceMultiMap(Object storage, Entity target) {
        try {
            synchronized (storage) {
                Object byClass = RegistryCleaner.findField(storage, "byClass", "Map");
                if (byClass instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>)byClass;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        Object k = entry.getKey();
                        if (!(k instanceof Class) || !((Class)k).isInstance(target)) continue;
                        Object list = entry.getValue();
                        if (list instanceof List) {
                            RegistryCleaner.safeListRemove((List) list, target);
                        }
                    }
                }
                Object allInstances = RegistryCleaner.findField(storage, "allInstances", "List");
                if (allInstances instanceof List) {
                    RegistryCleaner.safeListRemove((List) allInstances, target);
                }
                for (Field sf : FieldAccessUtil.safeGetDeclaredFields(storage.getClass())) {
                    try {
                        sf.setAccessible(true);
                        Object sfv = sf.get(storage);
                        if (sfv instanceof List) {
                            RegistryCleaner.safeListRemove((List) sfv, target);
                        }
                    } catch (Throwable throwable) {}
                }
            }
        } catch (Throwable throwable) {}
    }

    private static void safeListRemove(List list, Object target) {
        try {
            list.remove(target);
        } catch (java.util.ConcurrentModificationException cme) {

            try {
                ArrayList copy = new ArrayList(list);
                copy.remove(target);
                list.clear();
                list.addAll(copy);
            } catch (Throwable t) {}
        } catch (Throwable t) {}
    }

    private static Object findField(Object obj, String preferredName, String typeHint) {
        if (obj == null) {
            return null;
        }
        Class<?> clazz = obj.getClass();
        String[] names = SRG_NAMES.containsKey(preferredName) ? SRG_NAMES.get(preferredName) : new String[]{preferredName};
        for (String name : names) {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                }
                catch (Throwable throwable) {
                    continue;
                }
            }
        }
        for (clazz = obj.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field f : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                if (!f.getType().getSimpleName().contains(typeHint)) continue;
                try {
                    f.setAccessible(true);
                    return f.get(obj);
                }
                catch (Throwable throwable) {
                    }
            }
        }
        return null;
    }

    private static void removeUuidFromSet(Object obj, String fieldName, UUID uuid) {
        if (obj == null) {
            return;
        }
        for (Class<?> clazz = obj.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field f : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (!(val instanceof Set)) continue;
                    ((Set)val).remove(uuid);
                }
                catch (Throwable throwable) {
                    }
            }
        }
    }

    private static void removeFromAllMaps(Object obj, UUID uuid, int entityId) {
        if (obj == null) {
            return;
        }
        for (Class<?> clazz = obj.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            Field[] fieldsCopy;
            for (Field f : fieldsCopy = (Field[])FieldAccessUtil.safeGetDeclaredFields(clazz).clone()) {
                try {
                    String className;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    if (val instanceof Map) {
                        Map map = (Map)val;
                        map.remove(uuid);
                        map.remove(entityId);
                    }
                    if (!(className = val.getClass().getName()).contains("Int2Object")) continue;
                    try {
                        Method removeMethod = val.getClass().getMethod("remove", Integer.TYPE);
                        removeMethod.invoke(val, entityId);
                    }
                    catch (Throwable throwable) {}
                }
                catch (Throwable throwable) {
                    }
            }
        }
    }

    private static void removeEntityFromSections(Object sectionStorage, Entity target) {
        if (sectionStorage == null) {
            return;
        }
        for (Class<?> clazz = sectionStorage.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field f : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                try {
                    Method valuesMethod;
                    Object values;
                    String className;
                    f.setAccessible(true);
                    Object val = f.get(sectionStorage);
                    if (val == null || !(className = val.getClass().getName()).contains("Long2Object") || !((values = (valuesMethod = val.getClass().getMethod("values", new Class[0])).invoke(val, new Object[0])) instanceof Iterable)) continue;
                    Iterable iterable = (Iterable)values;
                    ArrayList sectionsCopy = new ArrayList();
                    for (Object section : iterable) {
                        sectionsCopy.add(section);
                    }
                    for (Object section : sectionsCopy) {
                        RegistryCleaner.removeFromSectionByClass(section, target);
                    }
                }
                catch (Throwable throwable) {
                    }
            }
        }
    }

    private static void purgeFromAllLevelStructures(Entity target, ServerLevel level) {
        if (level == null || target == null) return;
        UUID uuid = target.getUUID();
        int id = target.getId();
        for (Class<?> clazz = level.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field f : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(level);
                    if (val == null) continue;
                    if (val instanceof Collection) {
                        Collection col = (Collection) val;
                        try { col.remove(target); } catch (Throwable t) {}
                        try { col.remove(uuid); } catch (Throwable t) {}
                    } else if (val instanceof Map) {
                        Map map = (Map) val;
                        try { map.remove(target); } catch (Throwable t) {}
                        try { map.remove(uuid); } catch (Throwable t) {}
                        try { map.remove(id); } catch (Throwable t) {}
                        try {
                            map.values().remove(target);
                        } catch (Throwable t) {}
                    } else {
                        String className = val.getClass().getName();
                        if (className.contains("Int2Object")) {
                            try {
                                val.getClass().getMethod("remove", Integer.TYPE).invoke(val, id);
                            } catch (Throwable t) {}
                        }
                    }
                } catch (Throwable throwable) {}
            }
        }
    }

    private static void destroyAllCallbacks(Entity target) {
        if (target == null) return;
        for (Class<?> clazz = target.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field f : FieldAccessUtil.safeGetDeclaredFields(clazz)) {
                try {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (val == null) continue;
                    if (val instanceof EntityInLevelCallback) {
                        try {
                            ((EntityInLevelCallback) val).onRemove(Entity.RemovalReason.KILLED);
                        } catch (Throwable t) {}
                        try {
                            f.set(target, EntityInLevelCallback.NULL);
                        } catch (Throwable t) {}
                    } else if (val.getClass().getSimpleName().contains("Callback")
                            || val.getClass().getSimpleName().contains("LevelCallback")) {
                        try {
                            Method onRemove = val.getClass().getMethod("onRemove", Entity.RemovalReason.class);
                            onRemove.invoke(val, Entity.RemovalReason.KILLED);
                        } catch (Throwable t) {}
                        try {
                            f.set(target, null);
                        } catch (Throwable t) {}
                    }
                } catch (Throwable throwable) {}
            }
        }
    }

    static {
        HashMap<String, String[]> m = new HashMap<String, String[]>();
        m.put("entityManager", new String[]{"entityManager", "f_143244_"});
        m.put("sectionStorage", new String[]{"sectionStorage", "f_157495_"});
        m.put("visibleEntityStorage", new String[]{"visibleEntityStorage", "f_157494_"});
        m.put("knownUuids", new String[]{"knownUuids", "f_157491_"});
        m.put("entityMap", new String[]{"entityMap", "f_140175_"});
        m.put("entityTickList", new String[]{"entityTickList", "f_143243_"});
        m.put("active", new String[]{"active", "f_156903_"});
        m.put("passive", new String[]{"passive", "f_156904_"});
        m.put("storage", new String[]{"storage", "f_188348_"});
        m.put("byClass", new String[]{"byClass", "f_13524_"});
        m.put("allInstances", new String[]{"allInstances", "f_13525_"});
        m.put("byUuid", new String[]{"byUuid", "f_156808_"});
        m.put("byId", new String[]{"byId", "f_156807_"});
        SRG_NAMES = Map.copyOf(m);
    }
}

