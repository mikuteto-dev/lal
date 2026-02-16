package jp.mikumiku.lal.enforcement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraftforge.entity.PartEntity;
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
            if (tracked != null && (seenByField = RegistryCleaner.findAccessibleField(tracked.getClass(), "seenBy")) != null && (seenBy = seenByField.get(tracked)) instanceof Set) {
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
            Field validField = RegistryCleaner.findAccessibleField(target.getClass(), "valid");
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
            Method getCaps = RegistryCleaner.findAccessibleMethod(target.getClass(), "getCapabilities", new Class[0]);
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
    }

    private static void removeFromSectionByClass(Object section, Entity target) {
        try {
            Object storage = RegistryCleaner.findField(section, "storage", "ClassInstanceMultiMap");
            if (storage != null) {
                Object allInstances;
                Object byClass = RegistryCleaner.findField(storage, "byClass", "Map");
                if (byClass instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>)byClass;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        Object list;
                        Class clazz;
                        Map.Entry mapEntry = entry;
                        Object k = mapEntry.getKey();
                        if (!(k instanceof Class) || !(clazz = (Class)k).isInstance(target) || !((list = mapEntry.getValue()) instanceof List)) continue;
                        List l = (List)list;
                        l.remove(target);
                    }
                }
                if ((allInstances = RegistryCleaner.findField(storage, "allInstances", "List")) instanceof List) {
                    List l = (List)allInstances;
                    l.remove(target);
                }
                try {
                    for (Field sf : RegistryCleaner.safeGetDeclaredFields(storage.getClass())) {
                        try {
                            sf.setAccessible(true);
                            Object sfv = sf.get(storage);
                            if (!(sfv instanceof List)) continue;
                            List list = (List)sfv;
                            list.remove(target);
                        }
                        catch (Throwable throwable) {
                                    }
                    }
                }
                catch (Throwable throwable) {
                    }
            }
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
            for (Field f : RegistryCleaner.safeGetDeclaredFields(clazz)) {
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
            for (Field f : RegistryCleaner.safeGetDeclaredFields(clazz)) {
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
            for (Field f : fieldsCopy = (Field[])RegistryCleaner.safeGetDeclaredFields(clazz).clone()) {
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
            for (Field f : RegistryCleaner.safeGetDeclaredFields(clazz)) {
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

    private static Field[] safeGetDeclaredFields(Class<?> clazz) {
        try {
            return clazz.getDeclaredFields();
        }
        catch (Throwable t) {
            return new Field[0];
        }
    }

    static {
        HashMap<String, String[]> m = new HashMap<String, String[]>();
        m.put("entityManager", new String[]{"entityManager", "f_157650_"});
        m.put("sectionStorage", new String[]{"sectionStorage", "f_157489_"});
        m.put("visibleEntityStorage", new String[]{"visibleEntityStorage", "f_157491_"});
        m.put("knownUuids", new String[]{"knownUuids", "f_157490_"});
        m.put("entityMap", new String[]{"entityMap", "f_140175_"});
        m.put("entityTickList", new String[]{"entityTickList", "f_143249_"});
        m.put("active", new String[]{"active", "f_156918_"});
        m.put("passive", new String[]{"passive", "f_156919_"});
        m.put("storage", new String[]{"storage", "f_188348_"});
        m.put("byClass", new String[]{"byClass", "f_13524_"});
        m.put("allInstances", new String[]{"allInstances", "f_13525_"});
        m.put("byUuid", new String[]{"byUuid", "f_156817_"});
        m.put("byId", new String[]{"byId", "f_156816_"});
        SRG_NAMES = Map.copyOf(m);
    }
}

