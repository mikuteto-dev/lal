package jp.mikumiku.lal.item;

import jp.mikumiku.lal.entity.LALEntityManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class LALEntityRemoverItem extends Item {

    public LALEntityRemoverItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isCreative()) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        try {
            LALEntityManager.removeAllPermanent();
            player.sendSystemMessage(Component.literal("[LAL] All LAL Entities removed"));
        } catch (Throwable ignored) {}
        return InteractionResultHolder.success(stack);
    }
}
