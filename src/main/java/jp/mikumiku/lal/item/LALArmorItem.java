package jp.mikumiku.lal.item;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.core.LifePolicyEngine;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class LALArmorItem
extends ArmorItem {
    public LALArmorItem(ArmorMaterial material, ArmorItem.Type type) {
        super(material, type, new Item.Properties().fireResistant().stacksTo(1));
    }

    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (entity instanceof Player) {
            Player player = (Player)entity;
            if (!level.isClientSide()) {
                boolean hasLALArmor = false;
                for (ItemStack armorStack : player.getArmorSlots()) {
                    if (!(armorStack.getItem() instanceof LALArmorItem)) continue;
                    hasLALArmor = true;
                    break;
                }
                if (hasLALArmor) {
                    if (!CombatRegistry.isInImmortalSet((Entity)player)) {
                        LifePolicyEngine.requestImmortal(player.getUUID());
                    }
                    if (!player.getAbilities().mayfly) {
                        player.getAbilities().mayfly = true;
                        player.onUpdateAbilities();
                    }
                }
            }
        }
    }

    public void onArmorTick(ItemStack stack, Level level, Player player) {
        if (!level.isClientSide()) {
            if (!CombatRegistry.isInImmortalSet((Entity)player)) {
                LifePolicyEngine.requestImmortal(player.getUUID());
            }
            if (!player.getAbilities().mayfly) {
                player.getAbilities().mayfly = true;
                player.onUpdateAbilities();
            }
        }
    }

    public boolean isFoil(ItemStack stack) {
        return true;
    }

    public static void checkAndRemoveImmortality(Player player) {
        boolean hasLALArmor = false;
        for (ItemStack armorStack : player.getArmorSlots()) {
            if (!(armorStack.getItem() instanceof LALArmorItem)) continue;
            hasLALArmor = true;
            break;
        }
        if (!hasLALArmor) {
            CombatRegistry.removeFromImmortalSet(player.getUUID());
            if (!player.isCreative() && !player.isSpectator() && player.getAbilities().mayfly) {
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            }
        }
    }
}

