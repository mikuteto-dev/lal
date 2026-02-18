package jp.mikumiku.lal.transformer;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.EnumSet;
import java.util.Set;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

public class LALPlugin implements ILaunchPluginService {
    private static final Set<String> SKIP_PREFIXES = Set.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.objectweb.asm.", "org.spongepowered.", "cpw.mods.", "mixin.",
            "io.netty.", "com.google.", "org.apache.", "org.slf4j.", "ch.qos.",
            "org.lwjgl.", "it.unimi.dsi.", "org.joml.",
            "com.mojang.math.", "com.mojang.brigadier.", "com.mojang.serialization.",
            "com.mojang.datafixers.", "com.mojang.logging.", "com.mojang.authlib.",
            "com.mojang.blaze3d.",
            "oshi.", "com.electronwill.",
            "net.minecraft.util.", "net.minecraft.nbt.", "net.minecraft.tags.",
            "net.minecraft.resources.", "net.minecraft.core.",
            "net.minecraft.network.protocol.", "net.minecraft.network.chat.",
            "net.minecraft.commands.", "net.minecraft.advancements.",
            "net.minecraft.data.", "net.minecraft.locale.",
            "net.minecraftforge.",
            "jp.mikumiku.lal.transformer"
    );

    public LALPlugin() {
        super();
    }

    public String name() {
        return "zzz_lal_plugin";
    }

    public EnumSet<ILaunchPluginService.Phase> handlesClass(Type type, boolean isEmpty) {
        String className = type.getClassName();
        if (className == null) return EnumSet.noneOf(Phase.class);

        for (String prefix : SKIP_PREFIXES) {
            if (className.startsWith(prefix)) return EnumSet.noneOf(Phase.class);
        }

        return EnumSet.of(Phase.BEFORE, Phase.AFTER);
    }

    public boolean processClass(Phase phase, ClassNode classNode, Type classType, String reason) {
        if (!"classloading".equals(reason)) return false;
        return LALTransformer.transform(classNode, phase);
    }
}
