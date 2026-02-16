package jp.mikumiku.lal.transformer;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.EnumSet;
import java.util.Set;
import jp.mikumiku.lal.transformer.LALTransformer;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

public class LALPlugin
implements ILaunchPluginService {
    private static final Set<String> SKIP_PREFIXES = Set.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "org.objectweb.asm.", "org.spongepowered.", "cpw.mods.", "io.netty.", "com.google.", "org.apache.", "org.slf4j.", "ch.qos.", "org.lwjgl.", "it.unimi.dsi.", "com.mojang.math.", "com.mojang.brigadier.", "com.mojang.serialization.", "com.mojang.datafixers.", "com.mojang.logging.", "com.mojang.authlib.", "oshi.", "com.electronwill.", "org.joml.", "jp.mikumiku.lal.transformer");

    public LALPlugin() {
        super();
    }

    public String name() {
        return "zzz_lal_plugin";
    }

    public EnumSet<ILaunchPluginService.Phase> handlesClass(Type type, boolean isEmpty) {
        String className = type.getClassName();
        if (className == null) {
            return EnumSet.noneOf(ILaunchPluginService.Phase.class);
        }
        for (String prefix : SKIP_PREFIXES) {
            if (!className.startsWith(prefix)) continue;
            return EnumSet.noneOf(ILaunchPluginService.Phase.class);
        }
        return EnumSet.of(ILaunchPluginService.Phase.BEFORE, ILaunchPluginService.Phase.AFTER);
    }

    public boolean processClass(ILaunchPluginService.Phase phase, ClassNode classNode, Type classType, String reason) {
        if (!reason.equals("classloading")) {
            return false;
        }
        return LALTransformer.transform(classNode, phase);
    }
}

