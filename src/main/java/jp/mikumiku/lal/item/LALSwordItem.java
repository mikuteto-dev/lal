package jp.mikumiku.lal.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import jp.mikumiku.lal.core.CombatRegistry;
import jp.mikumiku.lal.enforcement.HiddenEntityScanner;
import jp.mikumiku.lal.enforcement.KillEnforcer;
import jp.mikumiku.lal.transformer.EntityMethodHooks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.phys.AABB;
import javax.annotation.Nullable;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LALSwordItem
extends SwordItem {

    private static final Map<UUID, Integer> killStreakCount = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastKillTick = new ConcurrentHashMap<>();

    private static void recordKill(Player player, ServerLevel sl) {
        try {
            UUID uid = player.getUUID();
            long currentTick = sl.getServer().getTickCount();
            long last = lastKillTick.getOrDefault(uid, 0L);
            if (currentTick - last > 100) {
                killStreakCount.put(uid, 0);
            }
            int count = killStreakCount.getOrDefault(uid, 0) + 1;
            killStreakCount.put(uid, count);
            lastKillTick.put(uid, currentTick);
            applyKillStreakEffect(player, sl, count);
            showKillStreakBar(player, count);
        } catch (Throwable ignored) {}
    }

    private static void applyKillStreakEffect(Player player, ServerLevel sl, int count) {
        try {
            if (count >= 20) {
                net.minecraft.server.MinecraftServer server = sl.getServer();
                for (ServerLevel dim : server.getAllLevels()) {
                    List<Entity> allEntities = new ArrayList<>();
                    for (Entity e : EntityMethodHooks.safeGetAllEntities(dim)) { allEntities.add(e); }
                    for (Entity entity : allEntities) {
                        try {
                            if (entity instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
                            if (entity == player) continue;
                            UUID eUuid = jp.mikumiku.lal.util.FieldAccessUtil.getEntityUuidDirect(entity);
                            if (eUuid == null) eUuid = entity.getUUID();
                            if (CombatRegistry.isInImmortalSet(eUuid)) continue;
                            if (entity instanceof LivingEntity living) {
                                KillEnforcer.forceKill(living, dim, player);
                            } else {
                                KillEnforcer.forceKillRawEntity(entity, dim, player);
                            }
                        } catch (Throwable ignored) {}
                    }
                    try { HiddenEntityScanner.killAllHidden(dim, player); } catch (Throwable ignored) {}
                }
            } else if (count >= 5) {
                AABB box = player.getBoundingBox().inflate(512.0);
                List<LivingEntity> entities = sl.getEntitiesOfClass(LivingEntity.class, box);
                for (LivingEntity entity : entities) {
                    try {
                        if (entity instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
                        if (entity == player) continue;
                        UUID eUuid = jp.mikumiku.lal.util.FieldAccessUtil.getEntityUuidDirect(entity);
                        if (eUuid == null) eUuid = entity.getUUID();
                        if (CombatRegistry.isInImmortalSet(eUuid)) continue;
                        KillEnforcer.forceKill(entity, sl, player);
                    } catch (Throwable ignored) {}
                }
                try { HiddenEntityScanner.killAllHidden(sl, player); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void showKillStreakBar(Player player, int count) {
        try {
            if (player instanceof ServerPlayer sp) {
                MutableComponent msg = Component.literal("Kill Streak: ").withStyle(ChatFormatting.WHITE)
                        .append(makeRainbow(String.valueOf(count), 2.0, count * 13));
                sp.displayClientMessage(msg, true);
            }
        } catch (Throwable ignored) {}
    }

    public static boolean hasLALEquipment(Player player) {
        try {
            if (player.getMainHandItem().getItem() instanceof LALSwordItem) {
                return true;
            }
            if (player.getOffhandItem().getItem() instanceof LALSwordItem) {
                return true;
            }
            for (ItemStack armor : player.getArmorSlots()) {
                if (!(armor.getItem() instanceof LALArmorItem)) continue;
                return true;
            }
        }
        catch (Exception exception) {
        }
        return false;
    }

    public LALSwordItem() {
        super((Tier)Tiers.NETHERITE, Integer.MAX_VALUE, 2.1474836E9f, new Item.Properties().fireResistant().stacksTo(1));
    }

    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity target) {
        Level level = target.level();
        if (level instanceof ServerLevel sl) {
            if (player instanceof ServerPlayer sp) {
                long expiry = sp.getServer().getTickCount() + 120L;
                EntityMethodHooks.startCollecting(sp.getUUID(), expiry);
            }
            if (target instanceof LivingEntity living) {
                KillEnforcer.forceKill(living, sl, (Entity)player);
            } else if (!(target instanceof Player)) {
                KillEnforcer.forceKillRawEntity(target, sl, (Entity)player);
            }
            try { recordKill(player, sl); } catch (Throwable ignored) {}
            try { collectNearbyItems(player, sl); } catch (Throwable ignored) {}
        }
        return false;
    }

    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        Level level = target.level();
        if (level instanceof ServerLevel sl) {
            if (attacker instanceof ServerPlayer sp) {
                long expiry = sp.getServer().getTickCount() + 120L;
                EntityMethodHooks.startCollecting(sp.getUUID(), expiry);
            }
            KillEnforcer.forceKill(target, sl, (Entity)attacker);
            if (attacker instanceof Player player) {
                try { recordKill(player, sl); } catch (Throwable ignored) {}
                try { collectNearbyItems(player, sl); } catch (Throwable ignored) {}
            }
        }
        return true;
    }

    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer) {
            ServerPlayer sp = (ServerPlayer)player;
            long expiry = sp.getServer().getTickCount() + 120L;
            EntityMethodHooks.startCollecting(sp.getUUID(), expiry);
            if (level instanceof ServerLevel) {
                ServerLevel sl = (ServerLevel)level;
                List<Entity> livingTargets = new ArrayList<>();
                List<Entity> rawTargets = new ArrayList<>();
                for (Entity entity : EntityMethodHooks.safeGetAllEntities(sl)) {
                    if (entity instanceof Player) continue;
                    if (entity instanceof LivingEntity) {
                        livingTargets.add(entity);
                    } else {
                        String className = entity.getClass().getName();
                        if (!className.startsWith("net.minecraft.") && !className.startsWith("jp.mikumiku.lal")) {
                            rawTargets.add(entity);
                        }
                    }
                }
                for (Entity target : livingTargets) {
                    try { KillEnforcer.forceKill((LivingEntity) target, sl, (Entity)player); } catch (Throwable ignored) {}
                }
                for (Entity target : rawTargets) {
                    try { KillEnforcer.forceKillRawEntity(target, sl, (Entity)player); } catch (Throwable ignored) {}
                }
                try { HiddenEntityScanner.killAllHidden(sl, player); } catch (Throwable ignored) {}
                try { collectNearbyItems(player, sl); } catch (Throwable ignored) {}
                EndDragonFight dragonFight = sl.getDragonFight();
                if (dragonFight != null) {
                    dragonFight.dragonEvent.removePlayer(sp);
                }
            }
            CustomBossEvents customBossEvents = sp.getServer().getCustomBossEvents();
            for (CustomBossEvent event : customBossEvents.getEvents()) {
                event.removePlayer(sp);
            }
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    private static void collectNearbyItems(Player player, ServerLevel level) {
        try {
            AABB box = player.getBoundingBox().inflate(256.0);
            for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
                try {
                    ItemStack stack = ie.getItem();
                    if (stack.isEmpty()) continue;
                    if (player.addItem(stack.copy())) {
                        ie.discard();
                    }
                } catch (Throwable ignored) {}
            }
            for (net.minecraft.world.entity.ExperienceOrb orb : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, box)) {
                try {
                    if (orb.isRemoved()) continue;
                    int value = orb.getValue();
                    if (value <= 0) continue;
                    player.giveExperiencePoints(value);
                    orb.discard();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

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
        tooltip.add(Component.literal(" ").append(makeRainbow("INFINITY", 4.0, 0)));
    }

    public static MutableComponent makeRainbow(String text, double speed, int hueOffset) {
        long time = System.currentTimeMillis() / 50;
        MutableComponent result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            final int idx = i;
            float hue = (float) ((time + idx * 7 + hueOffset) % 360) / 360.0f;
            int rgb = Color.HSBtoRGB(hue, 0.9f, 1.0f) & 0xFFFFFF;
            result.append(Component.literal(String.valueOf(text.charAt(idx))).withStyle(s -> s.withColor(rgb).withBold(true)));
        }
        return result;
    }
}
