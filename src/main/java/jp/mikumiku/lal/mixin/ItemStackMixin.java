package jp.mikumiku.lal.mixin;

import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={ItemStack.class}, priority=0x7FFFFFFF)
public abstract class ItemStackMixin {
    public ItemStackMixin() {
        super();
    }

    @Inject(method={"hurt"}, at={@At(value="HEAD")}, cancellable=true)
    private void lal$cancelDurabilityLoss(int amount, RandomSource random, @Nullable ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}

