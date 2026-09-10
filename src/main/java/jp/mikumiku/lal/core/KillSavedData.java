package jp.mikumiku.lal.core;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public class KillSavedData
extends SavedData {
    private static final String DATA_NAME = "lal_kill_data";
    private final Set<UUID> killedUuids = new HashSet<UUID>();

    public KillSavedData() {
        super();
    }

    public KillSavedData(CompoundTag tag) {
        super();
        ListTag list = tag.getList("killed", 10);
        for (int i = 0; i < list.size(); ++i) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("uuid")) continue;
            this.killedUuids.add(entry.getUUID("uuid"));
        }
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (UUID uuid : this.killedUuids) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("uuid", uuid);
            list.add(entry);
        }
        tag.put("killed", (Tag)list);
        return tag;
    }

    public void addKill(UUID uuid) {
        if (this.killedUuids.add(uuid)) {
            this.setDirty();
        }
    }

    public void removeKill(UUID uuid) {
        if (this.killedUuids.remove(uuid)) {
            this.setDirty();
        }
    }

    public Set<UUID> getKilledUuids() {
        return this.killedUuids;
    }

    public boolean isKilled(UUID uuid) {
        return this.killedUuids.contains(uuid);
    }

    public static KillSavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return (KillSavedData)overworld.getDataStorage().computeIfAbsent(KillSavedData::new, KillSavedData::new, DATA_NAME);
    }
}
