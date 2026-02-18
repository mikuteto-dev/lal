package jp.mikumiku.lal.client;

import com.mojang.blaze3d.platform.InputConstants;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "lal", value = {Dist.CLIENT})
public class LALClientHandler {
    private static final float BOOST_FLY_SPEED = 0.15f;
    private static final float NORMAL_FLY_SPEED = 0.05f;
    private static final float BOOST_WALK_SPEED = 0.2f;
    private static final float NORMAL_WALK_SPEED = 0.1f;
    private static boolean boostMode = false;
    public static final KeyMapping BOOST_KEY = new KeyMapping(
            "key.lal.boost_mode", InputConstants.Type.KEYSYM, 86, "key.categories.lal");

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DeathScreen) {
            if (LALSwordItem.hasLALEquipment((Player) player)
                    || CombatRegistry.isInImmortalSet(player.getUUID())) {
                mc.setScreen(null);
            }
        }

        if (LALSwordItem.hasLALEquipment((Player) player)) {
            if (player.getPose() == Pose.DYING) {
                player.setPose(Pose.STANDING);
                player.refreshDimensions();
            }
            if (player.deathTime > 0) player.deathTime = 0;
            if (player.hurtTime > 20) player.hurtTime = 0;
            try {
                float dataHealth = player.getEntityData().get(LivingEntity.DATA_HEALTH_ID);
                if (dataHealth <= 0.0f) {
                    float max = player.getMaxHealth();
                    if (max <= 0.0f) max = 20.0f;
                    EntityMethodHooks.setBypass(true);
                    try {
                        player.getEntityData().set(LivingEntity.DATA_HEALTH_ID, max);
                    } finally {
                        EntityMethodHooks.setBypass(false);
                    }
                }
            } catch (Exception ignored) {}
            player.noPhysics = false;
            player.setNoGravity(false);
        }
        while (BOOST_KEY.consumeClick()) {
            boostMode = !boostMode;
            if (boostMode) {
                player.displayClientMessage(Component.literal("[LAL] Boost Mode: ON"), true);
            } else {
                player.displayClientMessage(Component.literal("[LAL] Boost Mode: OFF"), true);
            }
        }
        if (boostMode) {
            player.getAbilities().setFlyingSpeed(BOOST_FLY_SPEED);
            player.getAbilities().walkingSpeed = BOOST_WALK_SPEED;
        } else {
            player.getAbilities().setFlyingSpeed(NORMAL_FLY_SPEED);
            player.getAbilities().walkingSpeed = NORMAL_WALK_SPEED;
        }
    }

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(BOOST_KEY);
    }

    public static void handleRemoveEntity(int entityId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                Entity entity = mc.level.getEntity(entityId);
                if (entity != null) {
                    entity.setRemoved(Entity.RemovalReason.KILLED);
                }
            }
        } catch (Exception ignored) {}
    }
}
