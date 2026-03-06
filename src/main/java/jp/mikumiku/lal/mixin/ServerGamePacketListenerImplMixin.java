package jp.mikumiku.lal.mixin;

import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.item.LALBreakerItem;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import jp.mikumiku.lal.util.MixinUtil;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerGamePacketListenerImpl.class, priority = 0x7FFFFFFF)
public class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void lal$earlyAttack(ServerboundInteractPacket packet, CallbackInfo ci) {
        try {
            ServerPlayer attacker = this.player;
            if (attacker == null) return;

            ServerLevel level = attacker.serverLevel();
            Entity target = packet.getTarget(level);
            if (target == null) return;

            Entity resolved = target;
            if (EntityMethodHooks.isPartEntity(target)) {
                Entity parent = EntityMethodHooks.getPartEntityParent(target);
                if (parent != null) {
                    resolved = parent;
                }
            }

            java.util.UUID targetUuid = resolved.getUUID();
            if (CombatRegistry.isInKillSet(targetUuid) || CombatRegistry.isDeadConfirmed(targetUuid)) {
                ci.cancel();
                return;
            }

            if (!(resolved instanceof LivingEntity)) return;

            boolean hasSword = attacker.getMainHandItem().getItem() instanceof LALSwordItem;
            boolean hasBreaker = LALBreakerItem.isHoldingBreaker(attacker);
            if (!hasSword && !hasBreaker) return;

            final boolean[] isAttack = {false};
            final boolean[] isInteraction = {false};
            packet.dispatch(new ServerboundInteractPacket.Handler() {
                @Override public void onInteraction(InteractionHand hand) { isInteraction[0] = true; }
                @Override public void onInteraction(InteractionHand hand, Vec3 pos) { isInteraction[0] = true; }
                @Override public void onAttack() { isAttack[0] = true; }
            });

            LivingEntity living = (LivingEntity) resolved;
            if (isAttack[0] || isInteraction[0]) {
                if (hasSword) {
                    KillEnforcer.forceKill(living, level, attacker);
                }
                if (hasBreaker) {
                    LALBreakerItem.asmBreakAttack(living, level, attacker);
                }
            }
        } catch (Exception ignored) {}
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void lal$onDisconnect(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
        try {
            jp.mikumiku.lal.transformer.EntityMethodHooks.onPlayerDisconnect(this.player);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void lal$protectMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        try {
            if (player == null) return;
            boolean isProtected = CombatRegistry.isInImmortalSet(player.getUUID()) ||
                    (LALSwordItem.hasLALEquipment(player) && !CombatRegistry.isInKillSet(player.getUUID()));
            if (!isProtected) return;
            if (ci.isCancelled()) {
                java.lang.reflect.Field f = MixinUtil.getCancelledField(ci);
                if (f != null) {
                    f.setBoolean(ci, false);
                }
            }
        } catch (Exception ignored) {}
    }
}
