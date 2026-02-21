package jp.mikumiku.lal.enforcement;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class TimeStopResistance {

    public static void registerEventHandlers() {
        MinecraftForge.EVENT_BUS.register(EventHandlers.class);
    }

    public static class EventHandlers {

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void onAttack(AttackEntityEvent event) {
            try {
                if (!event.isCanceled()) return;
                Player player = event.getEntity();
                if (LALSwordItem.hasLALEquipment(player)
                        && !CombatRegistry.isInKillSet(player.getUUID())) {
                    event.setCanceled(false);
                }
            } catch (Throwable ignored) {}
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
            uncancelIfProtected(event);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
            uncancelIfProtected(event);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            uncancelIfProtected(event);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
            uncancelIfProtected(event);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
            uncancelIfProtected(event);
        }

        private static void uncancelIfProtected(PlayerInteractEvent event) {
            try {
                if (!event.isCanceled()) return;
                Player player = event.getEntity();
                if (LALSwordItem.hasLALEquipment(player)
                        && !CombatRegistry.isInKillSet(player.getUUID())) {
                    event.setCanceled(false);
                }
            } catch (Throwable ignored) {}
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void onLivingAttack(LivingAttackEvent event) {
            try {
                LivingEntity entity = event.getEntity();
                if (!CombatRegistry.isInImmortalSet((Entity) entity)) return;
                if ("lal_attack".equals(event.getSource().getMsgId())) return;
                if (!event.isCanceled()) {
                    event.setCanceled(true);
                }
            } catch (Throwable ignored) {}
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void onLivingHurt(LivingHurtEvent event) {
            try {
                LivingEntity entity = event.getEntity();
                if (!CombatRegistry.isInImmortalSet((Entity) entity)) return;
                if ("lal_attack".equals(event.getSource().getMsgId())) return;
                event.setAmount(0.0f);
            } catch (Throwable ignored) {}
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void onLivingDeath(LivingDeathEvent event) {
            try {
                LivingEntity entity = event.getEntity();
                if (!CombatRegistry.isInImmortalSet((Entity) entity)) return;
                if ("lal_attack".equals(event.getSource().getMsgId())) return;
                if (!event.isCanceled()) {
                    event.setCanceled(true);
                }
            } catch (Throwable ignored) {}
        }
    }
}
