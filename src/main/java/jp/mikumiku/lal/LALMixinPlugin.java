package jp.mikumiku.lal;

import java.util.List;
import java.util.Set;
import jp.mikumiku.lal.transformer.LALTransformer;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public class LALMixinPlugin
implements IMixinConfigPlugin {
    public LALMixinPlugin() {
        super();
    }

    public void onLoad(String mixinPackage) {
    }

    public String getRefMapperConfig() {
        return "";
    }

    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    public List<String> getMixins() {
        return List.of();
    }

    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    static {
        LALTransformer.initialize();
    }
}

