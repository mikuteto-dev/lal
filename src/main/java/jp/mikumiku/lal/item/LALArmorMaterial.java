package jp.mikumiku.lal.item;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;

public class LALArmorMaterial
implements ArmorMaterial {
    public static final LALArmorMaterial INSTANCE = new LALArmorMaterial();
    private static final int[] DURABILITY = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
    private static final int[] DEFENSE = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};

    public LALArmorMaterial() {
        super();
    }

    public int getDurabilityForType(ArmorItem.Type type) {
        return DURABILITY[type.ordinal()];
    }

    public int getDefenseForType(ArmorItem.Type type) {
        return DEFENSE[type.ordinal()];
    }

    public int getEnchantmentValue() {
        return Integer.MAX_VALUE;
    }

    public SoundEvent getEquipSound() {
        return SoundEvents.ARMOR_EQUIP_NETHERITE;
    }

    public Ingredient getRepairIngredient() {
        return Ingredient.of((ItemLike[])new ItemLike[]{Items.NETHERITE_BLOCK});
    }

    public String getName() {
        return "lal";
    }

    public float getToughness() {
        return 2.1474836E9f;
    }

    public float getKnockbackResistance() {
        return 2.1474836E9f;
    }
}

