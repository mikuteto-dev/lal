package jp.mikumiku.lal.enforcement;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.util.FieldAccessUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.util.stream.Stream;

public class LALPersistentEntitySectionManager extends PersistentEntitySectionManager<Entity> {

    public LALPersistentEntitySectionManager(Class<Entity> entityClass,
                                              LevelCallback<Entity> levelCallback,
                                              EntityPersistentStorage<Entity> permanentStorage) {
        super(entityClass, levelCallback, permanentStorage);
    }

    @SuppressWarnings("unchecked")
    public static LALPersistentEntitySectionManager wrapExisting(PersistentEntitySectionManager<Entity> original) {
        try {
            Class<?> origClass = PersistentEntitySectionManager.class;
            Field callbackField = null;
            Field storageField = null;
            for (String name : new String[]{"f_157492_", "callbacks", "levelCallback"}) {
                try {
                    callbackField = origClass.getDeclaredField(name);
                    callbackField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            for (String name : new String[]{"f_157493_", "permanentStorage", "storage"}) {
                try {
                    storageField = origClass.getDeclaredField(name);
                    storageField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (callbackField == null || storageField == null) return null;

            LevelCallback<Entity> cb = (LevelCallback<Entity>) callbackField.get(original);
            EntityPersistentStorage<Entity> ps = (EntityPersistentStorage<Entity>) storageField.get(original);
            if (cb == null || ps == null) return null;

            LALPersistentEntitySectionManager lal = new LALPersistentEntitySectionManager(Entity.class, cb, ps);

            for (Field f : FieldAccessUtil.safeGetDeclaredFields(origClass)) {
                try {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (Modifier.isFinal(f.getModifiers())) {
                        f.setAccessible(true);
                        Object srcVal = f.get(original);
                        if (srcVal != null) {
                            Field destField = origClass.getDeclaredField(f.getName());
                            destField.setAccessible(true);
                            destField.set(lal, srcVal);
                        }
                        continue;
                    }
                    f.setAccessible(true);
                    Object srcVal = f.get(original);
                    if (srcVal != null) {
                        f.set(lal, srcVal);
                    }
                } catch (Throwable ignored) {}
            }
            return lal;
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public boolean addNewEntity(Entity entity) {
        if (entity == null) return false;
        try {
            UUID uuid = entity.getUUID();
            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
                return false;
            }
        } catch (Throwable ignored) {}
        return super.addNewEntity(entity);
    }

    @Override
    public void addLegacyChunkEntities(Stream<Entity> entities) {
        super.addLegacyChunkEntities(entities.filter(e -> {
            if (e == null) return false;
            try {
                UUID uuid = e.getUUID();
                return !CombatRegistry.isInKillSet(uuid) && !CombatRegistry.isDeadConfirmed(uuid);
            } catch (Throwable ignored) {}
            return true;
        }));
    }

    @Override
    public void addWorldGenChunkEntities(Stream<Entity> entities) {
        super.addWorldGenChunkEntities(entities.filter(e -> {
            if (e == null) return false;
            try {
                UUID uuid = e.getUUID();
                return !CombatRegistry.isInKillSet(uuid) && !CombatRegistry.isDeadConfirmed(uuid);
            } catch (Throwable ignored) {}
            return true;
        }));
    }

    @Override
    public boolean isLoaded(UUID uuid) {
        if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
            return false;
        }
        return super.isLoaded(uuid);
    }
}
