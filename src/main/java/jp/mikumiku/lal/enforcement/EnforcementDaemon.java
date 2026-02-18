package jp.mikumiku.lal.enforcement;

import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jp.mikumiku.lal.agent.LALAgentBridge;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import jp.mikumiku.lal.util.FieldAccessUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

public class EnforcementDaemon {

    private static final long INTERVAL_MS = 50;
    private static final int RETRANSFORM_INTERVAL = 200;

    private static volatile boolean running = false;
    private static Thread daemonThread = null;
    private static int loopCount = 0;

    private static final ConcurrentHashMap<UUID, WeakReference<LivingEntity>> trackedEntities = new ConcurrentHashMap<>();

    public static void start() {
        if (running && daemonThread != null && daemonThread.isAlive()) {
            return;
        }
        running = true;
        loopCount = 0;
        daemonThread = new Thread(EnforcementDaemon::run, "LAL-Enforcement");
        daemonThread.setDaemon(true);
        daemonThread.setPriority(Thread.MAX_PRIORITY);
        daemonThread.start();
    }

    public static void stop() {
        running = false;
    }

    public static void trackEntity(LivingEntity entity) {
        if (entity == null) return;
        try {
            trackedEntities.put(entity.getUUID(), new WeakReference<>(entity));
        } catch (Throwable t) {
        }
    }

    private static void run() {
        while (running) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }

            try {
                cleanupStaleReferences();
                processEntities();

                loopCount++;
                if (loopCount >= RETRANSFORM_INTERVAL) {
                    loopCount = 0;
                    checkHookCallsAndRetransform();
                }
            } catch (Throwable t) {
            }
        }
    }

    private static void cleanupStaleReferences() {
        try {
            Iterator<Map.Entry<UUID, WeakReference<LivingEntity>>> it = trackedEntities.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, WeakReference<LivingEntity>> entry = it.next();
                if (entry.getValue().get() == null) {
                    it.remove();
                }
            }
        } catch (Throwable t) {
        }
    }

    private static void processEntities() {
        for (Map.Entry<UUID, WeakReference<LivingEntity>> entry : trackedEntities.entrySet()) {
            try {
                LivingEntity entity = entry.getValue().get();
                if (entity == null) continue;

                UUID uuid = entry.getKey();

                if (CombatRegistry.isInImmortalSet(uuid)) {
                    enforceImmortal(entity);
                    enforceMovement(entity);
                } else if (CombatRegistry.isInKillSet(uuid)) {
                    enforceKill(entity);
                }
            } catch (Throwable t) {
            }
        }
    }

    private static void enforceImmortal(LivingEntity entity) {
        try {
            float maxHealth = entity.getMaxHealth();
            if (maxHealth <= 0.0f) {
                maxHealth = 20.0f;
            }

            if (FieldAccessUtil.HEALTH != null) {
                FieldAccessUtil.HEALTH.set(entity, maxHealth);
            }
            if (FieldAccessUtil.DEAD != null) {
                FieldAccessUtil.DEAD.set(entity, false);
            }
            if (FieldAccessUtil.DEATH_TIME != null) {
                FieldAccessUtil.DEATH_TIME.set(entity, 0);
            }
            if (FieldAccessUtil.HURT_TIME != null) {
                FieldAccessUtil.HURT_TIME.set(entity, 0);
            }
            if (FieldAccessUtil.REMOVAL_REASON != null) {
                Entity.RemovalReason reason = (Entity.RemovalReason) FieldAccessUtil.REMOVAL_REASON.get(entity);
                if (reason != null && entity.isAddedToWorld()) {
                    FieldAccessUtil.REMOVAL_REASON.set(entity, (Entity.RemovalReason) null);
                }
            }
        } catch (Throwable t) {
        }
    }

    private static void enforceKill(LivingEntity entity) {
        try {
            if (FieldAccessUtil.HEALTH != null) {
                FieldAccessUtil.HEALTH.set(entity, 0.0f);
            }
            if (FieldAccessUtil.DEAD != null) {
                FieldAccessUtil.DEAD.set(entity, true);
            }
            if (FieldAccessUtil.DEATH_TIME != null) {
                FieldAccessUtil.DEATH_TIME.set(entity, 20);
            }
            if (FieldAccessUtil.REMOVAL_REASON != null) {
                FieldAccessUtil.REMOVAL_REASON.set(entity, Entity.RemovalReason.KILLED);
            }
        } catch (Throwable t) {
        }
    }

    private static void enforceMovement(LivingEntity entity) {
        try {
            entity.noPhysics = false;
        } catch (Throwable t) {
        }

        try {
            EntityMethodHooks.setBypass(true);
            entity.setNoGravity(false);
        } catch (Throwable t) {
        } finally {
            EntityMethodHooks.setBypass(false);
        }

        try {
            AttributeInstance speedAttr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                for (AttributeModifier mod : new ArrayList<>(speedAttr.getModifiers())) {
                    if (mod.getAmount() < -0.05) {
                        speedAttr.removeModifier(mod);
                    }
                }
            }
        } catch (Throwable t) {
        }

        try {
            entity.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        } catch (Throwable t) {
        }
        try {
            entity.removeEffect(MobEffects.LEVITATION);
        } catch (Throwable t) {
        }
    }

    private static void checkHookCallsAndRetransform() {
        try {
            long calls = EntityMethodHooks.getAndResetHookCallCount();
            if (calls == 0 && !trackedEntities.isEmpty()) {
                triggerRetransform();
            }
        } catch (Throwable t) {
            triggerRetransform();
        }
    }

    private static void triggerRetransform() {
        try {
            Instrumentation inst = LALAgentBridge.getInstrumentation();
            if (inst == null) return;

            Class<?>[] targets = {
                Entity.class,
                LivingEntity.class,
                Player.class,
                ServerPlayer.class,
                ServerLevel.class
            };

            for (Class<?> target : targets) {
                try {
                    inst.retransformClasses(target);
                } catch (Throwable t) {
                }
            }
        } catch (Throwable t) {
        }
    }
}
