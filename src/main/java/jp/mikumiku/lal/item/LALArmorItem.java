package jp.mikumiku.lal.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.core.LifePolicyEngine;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import javax.annotation.Nullable;
import java.util.List;

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

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return "minecraft:textures/models/armor/lal_layer_" + (slot == EquipmentSlot.LEGS ? "2" : "1") + ".png";
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        return ImmutableMultimap.of();
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.empty());
        ArmorItem.Type armorType = this.getType();
        int[] baseColor;
        int[] peakColor;
        switch (armorType) {
            case HELMET:
                baseColor = new int[]{200, 50, 0};
                peakColor = new int[]{255, 180, 50};
                break;
            case CHESTPLATE:
                baseColor = new int[]{0, 80, 200};
                peakColor = new int[]{50, 200, 255};
                break;
            case LEGGINGS:
                baseColor = new int[]{0, 160, 60};
                peakColor = new int[]{80, 255, 140};
                break;
            default:
                baseColor = new int[]{80, 0, 200};
                peakColor = new int[]{255, 50, 255};
                break;
        }
        tooltip.add(Component.literal(" ").append(makePulse("Unknown", 0.6, baseColor, peakColor)));
    }

    private static MutableComponent makePulse(String text, double speed, int[] baseColor, int[] peakColor) {
        long time = System.currentTimeMillis() / 50;
        MutableComponent result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            final int idx = i;
            double wave = (Math.sin((time / speed) + idx * 0.8) + 1.0) / 2.0;
            int r = (int) (baseColor[0] + (peakColor[0] - baseColor[0]) * wave);
            int g = (int) (baseColor[1] + (peakColor[1] - baseColor[1]) * wave);
            int b = (int) (baseColor[2] + (peakColor[2] - baseColor[2]) * wave);
            int rgb = (r << 16) | (g << 8) | b;
            result.append(Component.literal(String.valueOf(text.charAt(idx))).withStyle(s -> s.withColor(rgb).withBold(true)));
        }
        return result;
    }

    public static void checkAndRemoveImmortality(Player player) {
        boolean hasLALEquip = LALSwordItem.hasLALEquipment(player);
        if (!hasLALEquip) {
            CombatRegistry.removeFromImmortalSet(player.getUUID());
        }
        boolean hasLALArmor = false;
        for (ItemStack armorStack : player.getArmorSlots()) {
            if (!(armorStack.getItem() instanceof LALArmorItem)) continue;
            hasLALArmor = true;
            break;
        }
        if (!hasLALArmor && !player.isCreative() && !player.isSpectator() && player.getAbilities().mayfly) {
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }
    }
}

