package jp.mikumiku.lal.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import jp.mikumiku.lal.core.BreakRegistry;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.enforcement.BreakEnforcer;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;
import javax.annotation.Nullable;

import java.util.List;
import java.util.UUID;

public class LALBreakerItem extends SwordItem {

    public LALBreakerItem() {
        super(Tiers.NETHERITE, 7, -2.4f, new Item.Properties().fireResistant().stacksTo(1));
    }

    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity target) {
        if (target instanceof LivingEntity) {
            LivingEntity living = resolveTarget(target);
            if (living != null) {
                Level level = target.level();
                if (level instanceof ServerLevel) {
                    performBreakAttack(living, (ServerLevel) level, player);
                }
            }
        }
        return false;
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        Level level = target.level();
        if (level instanceof ServerLevel && attacker instanceof Player) {
            LivingEntity resolved = resolveTarget(target);
            if (resolved != null) {
                performBreakAttack(resolved, (ServerLevel) level, (Player) attacker);
            }
        }
        return true;
    }

    private static void performBreakAttack(LivingEntity target, ServerLevel level, Player attacker) {
        try {
            UUID uuid = target.getUUID();

            if (CombatRegistry.isInKillSet(uuid) || CombatRegistry.isDeadConfirmed(uuid)) {
                return;
            }

            UUID attackerUuid = attacker.getUUID();
            if (!CombatRegistry.isInImmortalSet(attackerUuid)) {
                CombatRegistry.addToImmortalSet(attackerUuid);
            }

            float trueHealth = BreakEnforcer.readTrueHealth(target);
            if (Float.isNaN(trueHealth) || Float.isInfinite(trueHealth) || trueHealth <= 0.0f) {
                trueHealth = target.getMaxHealth();
                if (trueHealth <= 0.0f) trueHealth = 20.0f;
            }

            int tick = level.getServer().getTickCount();
            BreakRegistry.registerHit(uuid, trueHealth, trueHealth * 0.1f, tick);

            float healthCap = BreakRegistry.getHealthCap(uuid);

            BreakEnforcer.immediateEnforce(target, healthCap);

            if (healthCap <= 0.0f) {
                CombatRegistry.addToKillSet(uuid, attackerUuid, tick);
                CombatRegistry.setForcedHealth(uuid, 0.0f);
            }

        } catch (Throwable ignored) {}
    }

    private static LivingEntity resolveTarget(Entity target) {
        if (EntityMethodHooks.isPartEntity(target)) {
            Entity parent = EntityMethodHooks.getPartEntityParent(target);
            if (parent instanceof LivingEntity) {
                return (LivingEntity) parent;
            }
        }
        if (target instanceof LivingEntity) {
            return (LivingEntity) target;
        }
        return null;
    }

    public static boolean isHoldingBreaker(Player player) {
        try {
            if (player.getMainHandItem().getItem() instanceof LALBreakerItem) return true;
            if (player.getOffhandItem().getItem() instanceof LALBreakerItem) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static void asmBreakAttack(LivingEntity target, ServerLevel level, Player attacker) {
        performBreakAttack(target, level, attacker);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        return ImmutableMultimap.of();
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("item.modifiers.mainhand").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal(" ")
                .append(LALSwordItem.makeRainbow("10%", 4.0, 90))
                .append(Component.literal(" ").withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.translatable("attribute.name.generic.attack_damage")
                        .withStyle(ChatFormatting.DARK_GREEN)));
    }
}
