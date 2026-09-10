package jp.mikumiku.lal.transformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerActivity;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import java.util.LinkedHashSet;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;

/**
 * The mechanism Forge discovers from a mod jar. ModLauncher matches targets by exact class name
 * with no wildcard, so they are enumerated here; entity/level enforcement stays with Mixin,
 * which needs no list.
 */
public final class LALClassTransformer implements ITransformer<ClassNode> {

    /**
     * SynchedEntityData and its DataItem are deliberately absent. Entity.<clinit> calls
     * SynchedEntityData.defineId, whose first instructions are an unguarded
     * Thread.getStackTrace() and Class.forName - the pattern processSelfDefence keys on. A hook
     * call injected there runs while Entity is still initialising, so the hook class loads during
     * Bootstrap and pulls Player in behind it, and the client dies with
     * ClassNotFoundException: Player before any window appears. SynchedEntityData.set,
     * DataItem.setValue and DataItem.setDirty are already covered by SynchedEntityDataMixin and
     * SynchedEntityDataItemMixin.
     */
    private static final String[] TARGETS = {
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.Mob",
            "net.minecraft.world.entity.player.Player",
            "net.minecraft.world.entity.projectile.AbstractArrow",
            "net.minecraft.server.MinecraftServer",
            "net.minecraft.server.level.ServerLevel",
            "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.server.network.ServerGamePacketListenerImpl",
            "net.minecraft.server.players.PlayerList",
            "net.minecraft.world.item.ItemStack",
            "net.minecraft.world.level.entity.EntityLookup",
            "net.minecraft.world.level.entity.EntitySection",
            "net.minecraft.world.level.entity.EntityTickList",
            "net.minecraft.world.level.entity.PersistentEntitySectionManager",
            "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback",
            "net.minecraftforge.fml.ModList",
    };

    /**
     * Client-only; a dedicated server never loads them.
     */
    private static final String[] CLIENT_TARGETS = {
            "net.minecraft.client.renderer.LevelRenderer",
            "net.minecraft.client.renderer.GameRenderer",
    };

    /** For the target-name check; client-only names do not resolve on a server. */
    public static String[] targetsForValidation() {
        return TARGETS.clone();
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        // A null phase runs both ends in one pass, which the agent path already relied on.
        LALTransformer.transform(input, null);
        return input;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        // TransformerClassWriter.getCommonSuperClass asks ModLauncher to rebuild a class with this
        // reason purely to resolve a supertype while computing frames for a different class. The
        // bytes written in that pass are not the ones that get loaded (ClassTransformer drops
        // COMPUTE_FRAMES for it), so injecting here only adds hook references that pass then has to
        // resolve - which is how ServerLevel.tick ended up looking for Player and failing with
        // ClassNotFoundException. The class is transformed normally at classloading time.
        if (ITransformerActivity.COMPUTING_FRAMES_REASON.equals(context.getReason())) {
            return TransformerVoteResult.NO;
        }
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        Set<Target> targets = new LinkedHashSet<>();
        for (String name : TARGETS) {
            targets.add(Target.targetClass(name));
        }
        for (String name : CLIENT_TARGETS) {
            targets.add(Target.targetClass(name));
        }
        return targets;
    }
}
