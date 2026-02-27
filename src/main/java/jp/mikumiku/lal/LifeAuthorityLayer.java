package jp.mikumiku.lal;

import java.util.UUID;
import jp.mikumiku.lal.agent.LALAgentLoader;
import jp.mikumiku.lal.client.LALClientHandler;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.enforcement.DaemonWatchdog;
import jp.mikumiku.lal.enforcement.EnforcementDaemon;
import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.enforcement.TimeStopResistance;
import jp.mikumiku.lal.item.LALArmorItem;
import jp.mikumiku.lal.item.LALArmorMaterial;
import jp.mikumiku.lal.item.LALBowItem;
import jp.mikumiku.lal.item.LALBreakerItem;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.network.LALNetwork;
import jp.mikumiku.lal.transformer.LALTransformer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryObject;
@Mod(value="lal")
public class LifeAuthorityLayer {
    public static final String MOD_ID = "lal";
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create((IForgeRegistry)ForgeRegistries.ITEMS, (String)"lal");
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create((ResourceKey)Registries.CREATIVE_MODE_TAB, (String)"lal");
    public static final RegistryObject<Item> LAL_SWORD = ITEMS.register("lal_sword", LALSwordItem::new);
    public static final RegistryObject<Item> LAL_BOW = ITEMS.register("lal_bow", LALBowItem::new);
    public static final RegistryObject<Item> LAL_BREAKER = ITEMS.register("lal_breaker", LALBreakerItem::new);
    public static final RegistryObject<Item> LAL_HELMET = ITEMS.register("lal_helmet", () -> new LALArmorItem(LALArmorMaterial.INSTANCE, ArmorItem.Type.HELMET));
    public static final RegistryObject<Item> LAL_CHESTPLATE = ITEMS.register("lal_chestplate", () -> new LALArmorItem(LALArmorMaterial.INSTANCE, ArmorItem.Type.CHESTPLATE));
    public static final RegistryObject<Item> LAL_LEGGINGS = ITEMS.register("lal_leggings", () -> new LALArmorItem(LALArmorMaterial.INSTANCE, ArmorItem.Type.LEGGINGS));
    public static final RegistryObject<Item> LAL_BOOTS = ITEMS.register("lal_boots", () -> new LALArmorItem(LALArmorMaterial.INSTANCE, ArmorItem.Type.BOOTS));
    public static final RegistryObject<CreativeModeTab> LAL_TAB = CREATIVE_TABS.register("lal_tab", () -> CreativeModeTab.builder().title((Component)Component.translatable((String)"itemGroup.lal")).icon(() -> new ItemStack((ItemLike)LAL_SWORD.get())).displayItems((params, output) -> {
        output.accept((ItemLike)LAL_SWORD.get());
        output.accept((ItemLike)LAL_BREAKER.get());
        output.accept((ItemLike)LAL_BOW.get());
        output.accept((ItemLike)LAL_HELMET.get());
        output.accept((ItemLike)LAL_CHESTPLATE.get());
        output.accept((ItemLike)LAL_LEGGINGS.get());
        output.accept((ItemLike)LAL_BOOTS.get());
    }).build());

    public LifeAuthorityLayer() {
        super();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        CREATIVE_TABS.register(modBus);
        MinecraftForge.EVENT_BUS.register((Object)this);
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
            TimeStopResistance.registerEventHandlers();
        } catch (Exception e) {}

        modBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                LALNetwork.register();
            } catch (Exception ignored) {}
        });
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !event.player.level().isClientSide()) {
            LALArmorItem.checkAndRemoveImmortality(event.player);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CombatRegistry.removeFromImmortalSet(event.getEntity().getUUID());
    }

    @SubscribeEvent(priority=EventPriority.LOWEST, receiveCanceled=true)
    public void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getEntity() instanceof AbstractArrow arrow) {
            if (arrow.getBaseDamage() >= 2.0E9) {
                event.setCanceled(false);
            }
        }
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        UUID uuid = entity.getUUID();
        if (CombatRegistry.isDeadConfirmed(uuid) && entity instanceof LivingEntity) {
            Level level;
            LivingEntity living = (LivingEntity)entity;
            if (!(entity instanceof ServerPlayer) && (level = event.getLevel()) instanceof ServerLevel) {
                ServerLevel sl = (ServerLevel)level;
                CombatRegistry.addToKillSet(uuid);
                KillEnforcer.executeKill(living, sl);
            }
        }
    }
}

