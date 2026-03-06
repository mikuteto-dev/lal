package jp.mikumiku.lal.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import jp.mikumiku.lal.core.DisableRemoveList;
import jp.mikumiku.lal.network.LALEntityEffectPacket;
import jp.mikumiku.lal.network.LALEntityRemovePacket;
import jp.mikumiku.lal.network.LALEntitySpawnPacket;
import jp.mikumiku.lal.network.LALEntitySyncPacket;
import jp.mikumiku.lal.network.LALNetwork;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class LALEntityManager {

    static { try { jp.mikumiku.lal.util.NativeLoader.ensureLoaded(); } catch (Throwable ignored) {} }

    private static native void nativeRegister(long hi, long lo);
    private static native void nativeUnregister(long hi, long lo);
    private static native void nativeUpdatePos(long hi, long lo, double x, double y, double z, float rotX, float rotY);
    private static native int nativeGetCount();
    private static native double[] nativeGetEntry(int index);
    private static native int nativeVerifyAndRestore(int javaCount);

    static final DisableRemoveList<LALEntityBody> bodies = new DisableRemoveList<>();
    static volatile boolean tickedThisFrame = false;
    static volatile int lastTickedTick = -1;
    static volatile boolean suppressRecovery = false;
    static MinecraftServer serverRef;
    private static volatile long lastEventBusTickMs = 0;
    private static volatile boolean savedOnShutdown = false;
    private static volatile LALEntityBody[] backupSnapshot = new LALEntityBody[0];
    private static volatile int lastFullResyncTick = 0;
    private static final ScheduledExecutorService fallbackExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("Thread-" + UUID.randomUUID().toString().substring(0, 8));
        return t;
    });

    public static void init() {
        fallbackExecutor.scheduleAtFixedRate(() -> {
            try { tickAll(); } catch (Throwable ignored) {}
        }, 50, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public static synchronized void register(LALEntityBody body) {
        suppressRecovery = false;
        for (LALEntityBody existing : bodies) {
            if (existing.id.equals(body.id)) return;
        }
        bodies.add(body);
        try {
            nativeRegister(body.id.getMostSignificantBits(), body.id.getLeastSignificantBits());
        } catch (Throwable ignored) {}
        try {
            broadcastSpawn(body);
        } catch (Throwable ignored) {}
    }

    public static synchronized void unregister(LALEntityBody body) {
        bodies.internalRemove(body);
        try {
            nativeUnregister(body.id.getMostSignificantBits(), body.id.getLeastSignificantBits());
        } catch (Throwable ignored) {}
    }

    public static synchronized void removeAll() {
        List<LALEntityBody> copy = new ArrayList<>(bodies);
        for (LALEntityBody body : copy) {
            try { body.remove(); } catch (Throwable ignored) {}
        }
        bodies.internalClear();
    }

    public static void resetSuppressRecovery() {
        suppressRecovery = false;
    }

    public static synchronized void removeAllPermanent() {
        suppressRecovery = true;
        backupSnapshot = new LALEntityBody[0];
        removeAll();
        try {
            if (serverRef != null) {
                ServerLevel overworld = serverRef.overworld();
                if (overworld != null) {
                    LALEntitySavedData data = LALEntitySavedData.getOrCreate(overworld);
                    data.bodyTags.clear();
                    data.setDirty();
                    try { clearPlayerPersistentData(overworld); } catch (Throwable ignored) {}
                    try { clearDirectFile(overworld); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized LALEntityBody getByUUID(UUID id) {
        for (LALEntityBody body : bodies) {
            if (body.id.equals(id)) return body;
        }
        return null;
    }

    public static void tickAll() {
        if (serverRef == null) return;
        int currentTick = serverRef.getTickCount();
        if (currentTick == lastTickedTick) return;
        lastTickedTick = currentTick;
        lastEventBusTickMs = System.currentTimeMillis();
        try { verifyStorageIntegrity(); } catch (Throwable ignored) {}
        try { verifyNativeRegistry(); } catch (Throwable ignored) {}
        List<LALEntityBody> copy;
        synchronized (LALEntityManager.class) {
            copy = new ArrayList<>(bodies);
        }
        List<LALEntityBody> toRemove = new ArrayList<>();
        boolean doCleanup = (currentTick % 20 == 0);
        for (LALEntityBody body : copy) {
            if (body.removed) {
                toRemove.add(body);
                continue;
            }
            if (body.level == null) {
                toRemove.add(body);
                continue;
            }
            net.minecraft.world.entity.LivingEntity target = body.target != null ? body.target.get() : null;
            if (target != null) {
                if (body.level != target.level() && target.level() instanceof ServerLevel sl) {
                    body.level = sl;
                }
                if (doCleanup) {
                    double dx = target.getX() - body.pos.x;
                    double dy = target.getY() - body.pos.y;
                    double dz = target.getZ() - body.pos.z;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > 1000.0) {
                        body.pos = new net.minecraft.world.phys.Vec3(target.getX(), target.getY() + 2.0, target.getZ());
                        body.posO = body.pos;
                    }
                }
            }
            try {
                body.tick();
                try {
                    nativeUpdatePos(body.id.getMostSignificantBits(), body.id.getLeastSignificantBits(),
                            body.pos.x, body.pos.y, body.pos.z, body.rotX, body.rotY);
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
        if (!toRemove.isEmpty()) {
            for (LALEntityBody body : toRemove) {
                try { body.remove(); } catch (Throwable ignored) {}
            }
        }
        try {
            synchronized (LALEntityManager.class) {
                List<LALEntityBody> alive = new ArrayList<>();
                for (LALEntityBody b : bodies) {
                    if (b != null && !b.removed) alive.add(b);
                }
                backupSnapshot = alive.toArray(new LALEntityBody[0]);
            }
        } catch (Throwable ignored) {}
        if (currentTick - lastFullResyncTick >= 200) {
            lastFullResyncTick = currentTick;
            try { broadcastFullResync(); } catch (Throwable ignored) {}
        }
    }

    private static void verifyStorageIntegrity() {
        if (suppressRecovery) return;
        try {
            synchronized (LALEntityManager.class) {
                int currentSize = bodies.size();
                LALEntityBody[] backup = backupSnapshot;
                if (currentSize == 0 && backup != null && backup.length > 0) {
                    for (LALEntityBody body : backup) {
                        if (body != null && !body.removed) {
                            bodies.add(body);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void broadcastFullResync() {
        if (serverRef == null) return;
        List<LALEntityBody> copy;
        synchronized (LALEntityManager.class) {
            copy = new ArrayList<>(bodies);
        }
        if (copy.isEmpty()) return;
        try {
            for (ServerLevel level : serverRef.getAllLevels()) {
                for (ServerPlayer player : level.players()) {
                    for (LALEntityBody body : copy) {
                        if (body.removed) continue;
                        try {
                            LALEntitySpawnPacket spawnPacket = new LALEntitySpawnPacket(body.id, body.pos.x, body.pos.y, body.pos.z);
                            LALNetwork.sendRedundant(spawnPacket, player);
                            LALEntitySyncPacket syncPacket = new LALEntitySyncPacket(
                                    body.id, body.pos.x, body.pos.y, body.pos.z,
                                    body.rotX, body.rotY, (byte) 0, body.walkAnimSpeed, (byte) 0
                            );
                            LALNetwork.sendRedundant(syncPacket, player);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void verifyNativeRegistry() {
        if (suppressRecovery) return;
        try {
            int javaCount;
            synchronized (LALEntityManager.class) {
                javaCount = bodies.size();
            }
            int nativeCount = nativeVerifyAndRestore(javaCount);
            if (nativeCount > javaCount && serverRef != null) {
                ServerLevel overworld = serverRef.overworld();
                for (int i = 0; i < nativeCount; i++) {
                    try {
                        double[] entry = nativeGetEntry(i);
                        if (entry == null || entry.length < 7) continue;
                        long hi = (long) entry[0];
                        long lo = (long) entry[1];
                        UUID nativeId = new UUID(hi, lo);
                        boolean found = false;
                        synchronized (LALEntityManager.class) {
                            for (LALEntityBody body : bodies) {
                                if (body.id.equals(nativeId)) {
                                    found = true;
                                    break;
                                }
                            }
                        }
                        if (!found) {
                            try {
                                net.minecraft.world.phys.Vec3 pos = new net.minecraft.world.phys.Vec3(entry[2], entry[3], entry[4]);
                                net.minecraft.nbt.CompoundTag fakeTag = new net.minecraft.nbt.CompoundTag();
                                fakeTag.putUUID("id", nativeId);
                                fakeTag.putDouble("posX", entry[2]);
                                fakeTag.putDouble("posY", entry[3]);
                                fakeTag.putDouble("posZ", entry[4]);
                                fakeTag.putFloat("rotX", (float) entry[5]);
                                fakeTag.putFloat("rotY", (float) entry[6]);
                                fakeTag.putDouble("dX", 0);
                                fakeTag.putDouble("dY", 0);
                                fakeTag.putDouble("dZ", 0);
                                fakeTag.putInt("tickCount", 0);
                                LALEntityBody.load(fakeTag, overworld);
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void saveAll(ServerLevel level) {
        try {
            LALEntitySavedData data = LALEntitySavedData.getOrCreate(level);
            data.bodyTags.clear();
            List<LALEntityBody> copy;
            synchronized (LALEntityManager.class) {
                copy = new ArrayList<>(bodies);
            }
            ListTag tagList = new ListTag();
            for (LALEntityBody body : copy) {
                try {
                    CompoundTag tag = new CompoundTag();
                    body.save(tag);
                    data.bodyTags.add(tag);
                    tagList.add(tag.copy());
                } catch (Throwable ignored) {}
            }
            data.setDirty();
            try { saveToPlayerPersistentData(level, tagList); } catch (Throwable ignored) {}
            try { saveToDirectFile(level, tagList); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void loadAll(ServerLevel level) {
        try {
            LALEntitySavedData data = LALEntitySavedData.getOrCreate(level);
            for (CompoundTag tag : data.bodyTags) {
                try {
                    LALEntityBody.load(tag, level);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void verifyFromSavedData() {
        if (suppressRecovery) return;
        try {
            boolean empty;
            synchronized (LALEntityManager.class) {
                empty = bodies.isEmpty();
            }
            if (!empty) return;
            int nativeCount = 0;
            try { nativeCount = nativeGetCount(); } catch (Throwable ignored) {}
            if (nativeCount > 0) return;
            if (serverRef == null) return;
            ServerLevel overworld = serverRef.overworld();
            if (overworld == null) return;
            loadAll(overworld);
            synchronized (LALEntityManager.class) {
                empty = bodies.isEmpty();
            }
            if (!empty) return;
            try { loadFromDirectFile(overworld); } catch (Throwable ignored) {}
            synchronized (LALEntityManager.class) {
                empty = bodies.isEmpty();
            }
            if (!empty) return;
            try { loadFromPlayerPersistentData(overworld); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void broadcastSync(LALEntityBody body) {
        try {
            LALEntitySyncPacket packet = new LALEntitySyncPacket(
                    body.id,
                    body.pos.x, body.pos.y, body.pos.z,
                    body.rotX, body.rotY,
                    (byte) 0,
                    body.walkAnimSpeed,
                    (byte) 0
            );
            sendToAllPlayers(packet);
        } catch (Throwable ignored) {}
    }

    public static void broadcastSpawn(LALEntityBody body) {
        try {
            LALEntitySpawnPacket packet = new LALEntitySpawnPacket(
                    body.id,
                    body.pos.x, body.pos.y, body.pos.z
            );
            sendToAllPlayers(packet);
        } catch (Throwable ignored) {}
    }

    public static void broadcastRemove(LALEntityBody body) {
        try {
            LALEntityRemovePacket packet = new LALEntityRemovePacket(body.id);
            sendToAllPlayers(packet);
        } catch (Throwable ignored) {}
    }

    public static void broadcastEffect(LALEntityBody body, int type, net.minecraft.world.phys.Vec3 start, net.minecraft.world.phys.Vec3 end) {
        try {
            LALEntityEffectPacket packet = new LALEntityEffectPacket(
                    (byte) type,
                    start.x, start.y, start.z,
                    end.x, end.y, end.z
            );
            sendToAllPlayers(packet);
        } catch (Throwable ignored) {}
    }

    private static void sendToAllPlayers(jp.mikumiku.lal.network.LALPacket packet) {
        if (serverRef == null) return;
        try {
            for (ServerLevel level : serverRef.getAllLevels()) {
                try {
                    for (ServerPlayer player : level.players()) {
                        try {
                            LALNetwork.sendRedundant(packet, player);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static volatile boolean initialized = false;

    public static void ensureInitialized(MinecraftServer server) {
        if (initialized) return;
        initialized = true;
        serverRef = server;
        savedOnShutdown = false;
        init();
        try {
            ServerLevel overworld = server.overworld();
            loadAll(overworld);
        } catch (Throwable ignored) {}
    }

    public static void onServerStoppingDirect() {
        try {
            if (serverRef != null && !savedOnShutdown) {
                saveAll(serverRef.overworld());
            }
        } catch (Throwable ignored) {}
        synchronized (LALEntityManager.class) {
            bodies.internalClear();
        }
        serverRef = null;
        savedOnShutdown = false;
        initialized = false;
    }

    public static void onPlayerLoginDirect(ServerPlayer player) {
        try {
            List<LALEntityBody> copy;
            synchronized (LALEntityManager.class) {
                copy = new ArrayList<>(bodies);
            }
            for (LALEntityBody body : copy) {
                if (body.removed) continue;
                try {
                    LALEntitySpawnPacket spawnPacket = new LALEntitySpawnPacket(
                            body.id, body.pos.x, body.pos.y, body.pos.z
                    );
                    LALNetwork.sendRedundant(spawnPacket, player);
                    LALEntitySyncPacket syncPacket = new LALEntitySyncPacket(
                            body.id, body.pos.x, body.pos.y, body.pos.z,
                            body.rotX, body.rotY, (byte) 0, body.walkAnimSpeed,
                            (byte) 0
                    );
                    LALNetwork.sendRedundant(syncPacket, player);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void onPlayerLogoutDirect(ServerPlayer player) {
        try {
            if (serverRef != null) {
                int remaining = 0;
                for (ServerLevel level : serverRef.getAllLevels()) {
                    remaining += level.players().size();
                }
                if (remaining <= 1) {
                    saveAll(serverRef.overworld());
                    savedOnShutdown = true;
                    removeAll();
                }
            }
        } catch (Throwable ignored) {}
    }

    public static class LALEntitySavedData extends SavedData {

        private static final String DATA_NAME = "lal_entities";

        final List<CompoundTag> bodyTags = new ArrayList<>();

        public LALEntitySavedData() {
            super();
        }

        @Override
        public CompoundTag save(CompoundTag compound) {
            ListTag list = new ListTag();
            for (CompoundTag tag : bodyTags) {
                list.add(tag);
            }
            compound.put("bodies", list);
            return compound;
        }

        public static LALEntitySavedData load(CompoundTag tag) {
            LALEntitySavedData data = new LALEntitySavedData();
            ListTag list = tag.getList("bodies", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                try {
                    data.bodyTags.add(list.getCompound(i));
                } catch (Throwable ignored) {}
            }
            return data;
        }

        public static LALEntitySavedData getOrCreate(ServerLevel level) {
            DimensionDataStorage storage = level.getDataStorage();
            return storage.computeIfAbsent(
                    LALEntitySavedData::load,
                    LALEntitySavedData::new,
                    DATA_NAME
            );
        }
    }

    private static final String PERSISTENT_KEY = "ll_d";

    private static void saveToPlayerPersistentData(ServerLevel level, ListTag tagList) {
        if (tagList.isEmpty()) return;
        if (serverRef == null) return;
        CompoundTag container = new CompoundTag();
        container.put("d", tagList);
        container.putLong("t", System.currentTimeMillis());
        for (ServerLevel sl : serverRef.getAllLevels()) {
            for (ServerPlayer player : sl.players()) {
                try {
                    player.getPersistentData().put(PERSISTENT_KEY, container.copy());
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void loadFromPlayerPersistentData(ServerLevel level) {
        if (serverRef == null) return;
        CompoundTag best = null;
        long bestTime = 0;
        for (ServerLevel sl : serverRef.getAllLevels()) {
            for (ServerPlayer player : sl.players()) {
                try {
                    CompoundTag pd = player.getPersistentData();
                    if (!pd.contains(PERSISTENT_KEY, Tag.TAG_COMPOUND)) continue;
                    CompoundTag container = pd.getCompound(PERSISTENT_KEY);
                    long t = container.getLong("t");
                    if (t > bestTime) {
                        bestTime = t;
                        best = container;
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (best == null || !best.contains("d", Tag.TAG_LIST)) return;
        ListTag list = best.getList("d", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                LALEntityBody.load(list.getCompound(i), level);
            } catch (Throwable ignored) {}
        }
    }

    private static void clearPlayerPersistentData(ServerLevel level) {
        if (serverRef == null) return;
        for (ServerLevel sl : serverRef.getAllLevels()) {
            for (ServerPlayer player : sl.players()) {
                try {
                    player.getPersistentData().remove(PERSISTENT_KEY);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static File getDirectFile() {
        if (serverRef == null) return null;
        try {
            File worldDir = serverRef.getWorldPath(LevelResource.ROOT).toFile();
            return new File(worldDir, "ll_state.dat");
        } catch (Throwable ignored) {}
        return null;
    }

    private static void saveToDirectFile(ServerLevel level, ListTag tagList) {
        if (tagList.isEmpty()) return;
        File file = getDirectFile();
        if (file == null) return;
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            CompoundTag root = new CompoundTag();
            root.put("d", tagList);
            root.putLong("t", System.currentTimeMillis());
            NbtIo.writeCompressed(root, tmp);
            if (file.exists()) file.delete();
            tmp.renameTo(file);
        } catch (Throwable ignored) {
            try { tmp.delete(); } catch (Throwable t) {}
        }
    }

    private static void loadFromDirectFile(ServerLevel level) {
        File file = getDirectFile();
        if (file == null || !file.exists()) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file);
            if (root == null || !root.contains("d", Tag.TAG_LIST)) return;
            ListTag list = root.getList("d", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                try {
                    LALEntityBody.load(list.getCompound(i), level);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void clearDirectFile(ServerLevel level) {
        File file = getDirectFile();
        if (file != null && file.exists()) {
            try { file.delete(); } catch (Throwable ignored) {}
        }
    }
}
