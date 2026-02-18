package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.entity.PartEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={Player.class}, priority=0x7FFFFFFF)
public abstract class PlayerMixin {
    public PlayerMixin() {
        super();
    }

    @Inject(method={"attack"}, at={@At(value="HEAD")})
    private void lal$earlyAttack(Entity target, CallbackInfo ci) {
        Player self = (Player)(Object)this;
        ItemStack held = self.getMainHandItem();
        if (!(held.getItem() instanceof LALSwordItem)) {
            return;
        }
        Level level = target.level();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        ServerLevel sl = (ServerLevel)level;
        Entity resolved = target;
        if (target instanceof PartEntity) {
            Entity parent = ((PartEntity<?>)target).getParent();
            if (parent != null) {
                resolved = parent;
            }
        }
        if (resolved instanceof LivingEntity) {
            LivingEntity living = (LivingEntity)resolved;
            KillEnforcer.forceKill(living, sl, (Entity)self);
        }
    }
}

