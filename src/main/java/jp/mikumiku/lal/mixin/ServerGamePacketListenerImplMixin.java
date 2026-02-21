package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.item.LALBreakerItem;
import jp.mikumiku.lal.item.LALSwordItem;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerGamePacketListenerImpl.class, priority = 0x7FFFFFFF)
public class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleInteract", at = @At("HEAD"))
    private void lal$earlyAttack(ServerboundInteractPacket packet, CallbackInfo ci) {
        try {
            ServerPlayer attacker = this.player;
            if (attacker == null) return;

            boolean hasSword = attacker.getMainHandItem().getItem() instanceof LALSwordItem;
            boolean hasBreaker = LALBreakerItem.isHoldingBreaker(attacker);
            if (!hasSword && !hasBreaker) return;

            ServerLevel level = attacker.serverLevel();
            Entity target = packet.getTarget(level);
            if (target == null) return;

            Entity resolved = target;
            if (target instanceof PartEntity) {
                Entity parent = ((PartEntity<?>)target).getParent();
                if (parent != null) {
                    resolved = parent;
                }
            }
            if (!(resolved instanceof LivingEntity)) return;

            final boolean[] isAttack = {false};
            packet.dispatch(new ServerboundInteractPacket.Handler() {
                @Override public void onInteraction(InteractionHand hand) {}
                @Override public void onInteraction(InteractionHand hand, Vec3 pos) {}
                @Override public void onAttack() { isAttack[0] = true; }
            });

            if (isAttack[0]) {
                LivingEntity living = (LivingEntity) resolved;
                if (hasSword) {
                    KillEnforcer.forceKill(living, level, attacker);
                }
                if (hasBreaker) {
                    LALBreakerItem.asmBreakAttack(living, level, attacker);
                }
            }
        } catch (Exception ignored) {}
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void lal$protectMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        try {
            if (player == null) return;
            boolean isProtected = CombatRegistry.isInImmortalSet(player.getUUID()) ||
                    (LALSwordItem.hasLALEquipment(player) && !CombatRegistry.isInKillSet(player.getUUID()));
            if (!isProtected) return;
            if (ci.isCancelled()) {
                java.lang.reflect.Field f = CallbackInfo.class.getDeclaredField("cancelled");
                f.setAccessible(true);
                f.setBoolean(ci, false);
            }
        } catch (Exception ignored) {}
    }
}
