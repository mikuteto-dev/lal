package jp.mikumiku.lal.item;

import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.item.LALArmorItem;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import java.util.ArrayList;
import java.util.List;

public class LALSwordItem
extends SwordItem {
    public static boolean hasLALEquipment(Player player) {
        try {
            if (player.getMainHandItem().getItem() instanceof LALSwordItem) {
                return true;
            }
            if (player.getOffhandItem().getItem() instanceof LALSwordItem) {
                return true;
            }
            for (ItemStack armor : player.getArmorSlots()) {
                if (!(armor.getItem() instanceof LALArmorItem)) continue;
                return true;
            }
        }
        catch (Exception exception) {
        }
        return false;
    }

    public LALSwordItem() {
        super((Tier)Tiers.NETHERITE, Integer.MAX_VALUE, 2.1474836E9f, new Item.Properties().fireResistant().stacksTo(1));
    }

    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity target) {
        if (target instanceof LivingEntity) {
            LivingEntity living = (LivingEntity)target;
            Level level = target.level();
            if (level instanceof ServerLevel) {
                ServerLevel sl = (ServerLevel)level;
                KillEnforcer.forceKill(living, sl, (Entity)player);
            }
        }
        return false;
    }

    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        Level level = target.level();
        if (level instanceof ServerLevel) {
            ServerLevel sl = (ServerLevel)level;
            KillEnforcer.forceKill(target, sl, (Entity)attacker);
        }
        return true;
    }

    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer) {
            ServerPlayer sp = (ServerPlayer)player;
            if (level instanceof ServerLevel) {
                ServerLevel sl = (ServerLevel)level;
                List<LivingEntity> targets = new ArrayList<>();
                for (Entity entity : sl.getAllEntities()) {
                    if (entity instanceof LivingEntity && !(entity instanceof Player)) {
                        targets.add((LivingEntity) entity);
                    }
                }
                for (LivingEntity target : targets) {
                    KillEnforcer.forceKill(target, sl, (Entity)player);
                }
                EndDragonFight dragonFight = sl.getDragonFight();
                if (dragonFight != null) {
                    dragonFight.dragonEvent.removePlayer(sp);
                }
            }
            CustomBossEvents customBossEvents = sp.getServer().getCustomBossEvents();
            for (CustomBossEvent event : customBossEvents.getEvents()) {
                event.removePlayer(sp);
            }
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    public boolean isFoil(ItemStack stack) {
        return true;
    }
}

