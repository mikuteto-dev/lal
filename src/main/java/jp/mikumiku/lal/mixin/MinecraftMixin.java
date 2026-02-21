package jp.mikumiku.lal.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.item.LALSwordItem;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    private static Field lal$timerField;
    private static Field lal$msPerTickField;
    private static Field lal$deadField;
    private static Field lal$deathTimeField;
    private static boolean lal$timerFieldsResolved = false;
    private static boolean lal$entityFieldsResolved = false;

    private static Method lal$pickMethod;
    private static Method lal$gameModeTickMethod;
    private static boolean lal$tickMethodsResolved = false;

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void lal$blockDeathScreen(Screen screen, CallbackInfo ci) {
        if (screen instanceof DeathScreen) {
            Minecraft mc = (Minecraft)(Object)this;
            LocalPlayer player = mc.player;
            if (player != null && shouldPlayerBeAlive(player)) {
                lal$forceAliveOnClient(player);
                ci.cancel();
            }
        }
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void lal$onRunTick(boolean renderLevel, CallbackInfo ci) {
        Minecraft mc = (Minecraft)(Object)this;
        if (mc.player == null) return;
        if (!shouldPlayerBeAlive(mc.player)) return;

        lal$forceAliveOnClient(mc.player);

        if (mc.screen instanceof DeathScreen) {
            mc.setScreen(null);
        }

        try {
            if (!lal$timerFieldsResolved) {
                lal$timerFieldsResolved = true;
                for (String name : new String[]{"f_91021_", "timer"}) {
                    try {
                        lal$timerField = Minecraft.class.getDeclaredField(name);
                        lal$timerField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                if (lal$timerField == null) return;
                Object timer = lal$timerField.get(mc);
                if (timer == null) return;
                for (String name : new String[]{"msPerTick", "f_92523_"}) {
                    try {
                        lal$msPerTickField = timer.getClass().getDeclaredField(name);
                        lal$msPerTickField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (lal$timerField == null || lal$msPerTickField == null) return;
            Object timer = lal$timerField.get(mc);
            if (timer == null) return;
            float msPerTick = lal$msPerTickField.getFloat(timer);

            if (msPerTick > 100.0f) {
                lal$forceClientTick(mc);
            }
        } catch (Exception ignored) {}

        if (EntityMethodHooks.clientLastTickedNano > 0) {
            long elapsed = System.nanoTime() - EntityMethodHooks.clientLastTickedNano;
            if (elapsed > 150_000_000L) {
                lal$forceClientTick(mc);
            }
        }
    }

    private static void lal$forceClientTick(Minecraft mc) {
        try {
            if (!lal$tickMethodsResolved) {
                lal$tickMethodsResolved = true;
                if (mc.gameRenderer != null) {
                    for (String name : new String[]{"m_109093_", "pick"}) {
                        try {
                            lal$pickMethod = mc.gameRenderer.getClass().getDeclaredMethod(name, float.class);
                            lal$pickMethod.setAccessible(true);
                            break;
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
                if (mc.gameMode != null) {
                    for (String name : new String[]{"m_105190_", "tick"}) {
                        try {
                            lal$gameModeTickMethod = mc.gameMode.getClass().getDeclaredMethod(name);
                            lal$gameModeTickMethod.setAccessible(true);
                            break;
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
            }

            if (lal$pickMethod != null && mc.gameRenderer != null) {
                try { lal$pickMethod.invoke(mc.gameRenderer, 1.0f); } catch (Throwable ignored) {}
            }

            if (lal$gameModeTickMethod != null && mc.gameMode != null) {
                try { lal$gameModeTickMethod.invoke(mc.gameMode); } catch (Throwable ignored) {}
            }

            if (mc.player != null) {
                try { mc.player.tick(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void lal$forceAliveOnClient(LocalPlayer player) {
        try {
            float health = player.getHealth();
            if (health <= 0.0f) {
                float max = player.getMaxHealth();
                if (max <= 0.0f) max = 20.0f;
                player.setHealth(max);
            }

            if (!lal$entityFieldsResolved) {
                lal$entityFieldsResolved = true;
                for (String name : new String[]{"dead", "f_20960_"}) {
                    try {
                        lal$deadField = LivingEntity.class.getDeclaredField(name);
                        lal$deadField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
                for (String name : new String[]{"deathTime", "f_20962_"}) {
                    try {
                        lal$deathTimeField = LivingEntity.class.getDeclaredField(name);
                        lal$deathTimeField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (lal$deadField != null) {
                lal$deadField.setBoolean(player, false);
            }
            if (lal$deathTimeField != null) {
                lal$deathTimeField.setInt(player, 0);
            }
        } catch (Exception ignored) {}
    }

    private static boolean shouldPlayerBeAlive(LocalPlayer player) {
        try {
            if (LALSwordItem.hasLALEquipment((Player) player)) return true;
            if (CombatRegistry.isInImmortalSet(player.getUUID())) return true;
        } catch (Exception ignored) {}
        return false;
    }
}
