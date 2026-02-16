package jp.mikumiku.lal.damage;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

public class LALDamageSources {
    public static final ResourceKey<DamageType> LAL_ATTACK = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("lal", "lal_attack"));

    public LALDamageSources() {
        super();
    }

    public static DamageSource lalAttack(ServerLevel level) {
        Registry<DamageType> registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource((Holder<DamageType>)registry.getHolderOrThrow(LAL_ATTACK));
    }

    public static DamageSource lalAttack(ServerLevel level, Entity attacker) {
        Registry<DamageType> registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource((Holder<DamageType>)registry.getHolderOrThrow(LAL_ATTACK), attacker);
    }
}
