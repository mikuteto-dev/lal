package jp.mikumiku.lal.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(value = ChunkSerializer.class, priority = Integer.MAX_VALUE)
public class ChunkSerializerMixin {

    @Inject(method = "write", at = @At("RETURN"))
    private static void lal$protectSavedEntities(
            ServerLevel level, ChunkAccess chunk,
            CallbackInfoReturnable<CompoundTag> cir) {
    }
}
