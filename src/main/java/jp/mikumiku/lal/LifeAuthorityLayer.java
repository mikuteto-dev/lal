package jp.mikumiku.lal;

import java.util.UUID;
import jp.mikumiku.lal.agent.LALAgentLoader;
import jp.mikumiku.lal.client.LALClientHandler;
import jp.mikumiku.lal.enforcement.DaemonWatchdog;
import jp.mikumiku.lal.enforcement.EnforcementDaemon;
import jp.mikumiku.lal.entity.LALSlashProjectile;
import jp.mikumiku.lal.item.LALArmorMaterial;
import jp.mikumiku.lal.item.LALBowItem;
import jp.mikumiku.lal.item.LALBreakerItem;
import jp.mikumiku.lal.item.LALEntityRemoverItem;
import jp.mikumiku.lal.item.LALEntitySpawnerItem;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.item.LALArmorItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryObject;
@Mod(value="lal")
public class LifeAuthorityLayer {
    public static final String MOD_ID = "lal";
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create((IForgeRegistry)ForgeRegistries.ITEMS, (String)"lal");
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create((ResourceKey)Registries.CREATIVE_MODE_TAB, (String)"lal");
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, "lal");
    public static final RegistryObject<EntityType<LALSlashProjectile>> LAL_SLASH_PROJECTILE = ENTITY_TYPES.register("lal_slash_projectile",
            () -> EntityType.Builder.<LALSlashProjectile>of(
                    (type, level) -> new LALSlashProjectile(type, level),
                    MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .build("lal_slash_projectile"));
    public static final RegistryObject<Item> LAL_SWORD = ITEMS.register("lal_sword", LALSwordItem::new);
    public static final RegistryObject<Item> LAL_BOW = ITEMS.register("lal_bow", LALBowItem::new);
    public static final RegistryObject<Item> LAL_BREAKER = ITEMS.register("lal_breaker", LALBreakerItem::new);
    public static final RegistryObject<Item> LAL_HELMET = ITEMS.register("lal_helmet", () -> new LALArmorItem(LALArmorMaterial.INSTANCE, ArmorItem.Type.HELMET));
    public static final RegistryObject<Item> LAL_CHESTPLATE = ITEMS.register("lal_chestplate", () -> new LALArmorItem(LALArmorMaterial.INSTANCE, ArmorItem.Type.CHESTPLATE));
    public static final RegistryObject<Item> LAL_LEGGINGS = ITEMS.register("lal_leggings", () -> new LALArmorItem(LALArmorMaterial.INSTANCE, ArmorItem.Type.LEGGINGS));
    public static final RegistryObject<Item> LAL_BOOTS = ITEMS.register("lal_boots", () -> new LALArmorItem(LALArmorMaterial.INSTANCE, ArmorItem.Type.BOOTS));
    public static final RegistryObject<Item> LAL_ENTITY_SPAWNER = ITEMS.register("lal_entity_spawner", LALEntitySpawnerItem::new);
    public static final RegistryObject<Item> LAL_ENTITY_REMOVER = ITEMS.register("lal_entity_remover", LALEntityRemoverItem::new);
    public static final RegistryObject<Item> LAL_TAB_ICON = ITEMS.register("lal_tab_icon", () -> new Item(new Item.Properties()));
    public static final RegistryObject<CreativeModeTab> LAL_TAB = CREATIVE_TABS.register("lal_tab", () -> CreativeModeTab.builder().title((Component)Component.translatable((String)"itemGroup.lal")).icon(() -> new ItemStack((ItemLike)LAL_TAB_ICON.get())).displayItems((params, output) -> {
        output.accept((ItemLike)LAL_SWORD.get());
        output.accept((ItemLike)LAL_BREAKER.get());
        output.accept((ItemLike)LAL_BOW.get());
        output.accept((ItemLike)LAL_HELMET.get());
        output.accept((ItemLike)LAL_CHESTPLATE.get());
        output.accept((ItemLike)LAL_LEGGINGS.get());
        output.accept((ItemLike)LAL_BOOTS.get());
        output.accept((ItemLike)LAL_ENTITY_SPAWNER.get());
        output.accept((ItemLike)LAL_ENTITY_REMOVER.get());
    }).build());

    public LifeAuthorityLayer() {
        super();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        CREATIVE_TABS.register(modBus);
        ENTITY_TYPES.register(modBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(LALClientHandler::registerKeyMappings);
        }
        try {
            LALAgentLoader.load();
        } catch (Exception e) {}

        try {
            EnforcementDaemon.start();
        } catch (Exception e) {}

        try {
            DaemonWatchdog.start();
        } catch (Exception e) {}

        try {
            verifyFileSystemProviders();
        } catch (Throwable ignored) {}

        try {
            startFlagResetMonitor();
        } catch (Throwable ignored) {}
    }

    public static void verifyFileSystemProviders() {
        try {
            for (java.nio.file.spi.FileSystemProvider fsp : java.nio.file.spi.FileSystemProvider.installedProviders()) {
                try {
                    String className = fsp.getClass().getName();
                    if (className.startsWith("cpw.mods.") || className.startsWith("net.minecraftforge.")) {
                        java.lang.reflect.Field[] fields = fsp.getClass().getDeclaredFields();
                        for (java.lang.reflect.Field f : fields) {
                            try {
                                if (java.nio.file.spi.FileSystemProvider.class.isAssignableFrom(f.getType())) {
                                    f.setAccessible(true);
                                    Object delegate = f.get(fsp);
                                    if (delegate != null) {
                                        String delegateName = delegate.getClass().getName();
                                        if (!delegateName.startsWith("cpw.mods.")
                                                && !delegateName.startsWith("net.minecraftforge.")
                                                && !delegateName.startsWith("sun.")
                                                && !delegateName.startsWith("jdk.")
                                                && !delegateName.startsWith("java.")) {
                                            restoreOriginalProvider(fsp, f);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreOriginalProvider(java.nio.file.spi.FileSystemProvider wrapper, java.lang.reflect.Field delegateField) {
        try {
            Object hijacked = delegateField.get(wrapper);
            if (hijacked == null) return;
            java.lang.reflect.Field[] hijackedFields = hijacked.getClass().getDeclaredFields();
            for (java.lang.reflect.Field f : hijackedFields) {
                try {
                    if (java.nio.file.spi.FileSystemProvider.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object original = f.get(hijacked);
                        if (original != null) {
                            delegateField.set(wrapper, original);
                            return;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static volatile boolean flagsScanDone = false;
    private static final java.util.concurrent.CopyOnWriteArrayList<java.lang.reflect.Field> flagFields =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private static void startFlagResetMonitor() {
        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.interrupted();
                        continue;
                    }
                    try {
                        resetStaticBooleanFlags();
                    } catch (Throwable ignored) {}
                } catch (ThreadDeath td) { continue; }
            }
        }, "Thread-" + UUID.randomUUID().toString().substring(0, 8));
        monitor.setDaemon(true);
        monitor.setPriority(Thread.MIN_PRIORITY + 1);
        monitor.start();
    }

    private static void resetStaticBooleanFlags() {
        if (!flagsScanDone) {
            flagsScanDone = true;
            try {
                scanFlags();
            } catch (Throwable ignored) {}
        }
        for (java.lang.reflect.Field f : flagFields) {
            try {
                if (f.getBoolean(null)) {
                    f.setBoolean(null, false);
                }
            } catch (Throwable ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private static void scanFlags() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = LifeAuthorityLayer.class.getClassLoader();
            java.lang.reflect.Field classesField = null;
            try {
                classesField = ClassLoader.class.getDeclaredField("classes");
                classesField.setAccessible(true);
            } catch (Throwable ignored) {
                return;
            }
            Object vec = classesField.get(cl);
            if (!(vec instanceof java.util.Vector)) return;
            java.util.Vector<Class<?>> classes = (java.util.Vector<Class<?>>) vec;
            Class<?>[] snapshot = classes.toArray(new Class<?>[0]);
            for (Class<?> clazz : snapshot) {
                try {
                    String name = clazz.getName();
                    if (name.startsWith("java.") || name.startsWith("sun.")
                            || name.startsWith("jdk.") || name.startsWith("com.sun.")
                            || name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")
                            || name.startsWith("jp.mikumiku.lal.")) {
                        continue;
                    }
                    for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                        try {
                            if (f.getType() == boolean.class
                                    && java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                    && java.lang.reflect.Modifier.isPublic(f.getModifiers())) {
                                f.setAccessible(true);
                                String fn = f.getName().toLowerCase();
                                if (fn.contains("return") || fn.contains("disable")
                                        || fn.contains("bypass") || fn.contains("block")
                                        || fn.contains("cancel") || fn.contains("stop")) {
                                    flagFields.add(f);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

}

