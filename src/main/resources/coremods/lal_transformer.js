function initializeCoreMod() {
  var Opcodes = Java.type("org.objectweb.asm.Opcodes");
  var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
  var InsnNode = Java.type("org.objectweb.asm.tree.InsnNode");
  var VarInsnNode = Java.type("org.objectweb.asm.tree.VarInsnNode");
  var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
  var JumpInsnNode = Java.type("org.objectweb.asm.tree.JumpInsnNode");
  var LabelNode = Java.type("org.objectweb.asm.tree.LabelNode");
  var ASMAPI = Java.type("net.minecraftforge.coremod.api.ASMAPI");

  var HOOKS = "jp/mikumiku/lal/transformer/EntityMethodHooks";

  var MAPPINGS = [
    {
      srg: "m_21223_",
      mcp: "getHealth",
      desc: "()F",
      type: "FLOAT",
      headHook: "shouldReplaceMethod",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceGetHealth",
      replaceDesc: "(Ljava/lang/Object;)F",
      headStyle: "cancel_return",
    },
    {
      srg: "m_21224_",
      mcp: "isDeadOrDying",
      desc: "()Z",
      type: "BOOLEAN",
      headHook: "shouldReplaceMethod",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceIsDeadOrDying",
      replaceDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_return",
    },
    {
      srg: "m_6084_",
      mcp: "isAlive",
      desc: "()Z",
      type: "BOOLEAN",
      headHook: "shouldReplaceMethod",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceIsAlive",
      replaceDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_return",
    },
    {
      srg: "m_213877_",
      mcp: "isRemoved",
      desc: "()Z",
      type: "BOOLEAN",
      headHook: "shouldReplaceMethod",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceIsRemoved",
      replaceDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_return",
    },
    {
      srg: "m_146911_",
      mcp: "getRemovalReason",
      desc: "()Lnet/minecraft/world/entity/Entity$RemovalReason;",
      type: "OBJECT",
      headHook: "shouldReplaceMethod",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceGetRemovalReason",
      replaceDesc:
        "(Ljava/lang/Object;)Lnet/minecraft/world/entity/Entity$RemovalReason;",
      headStyle: "cancel_return",
    },
    {
      srg: "m_6087_",
      mcp: "canBeCollidedWith",
      desc: "()Z",
      type: "BOOLEAN",
      headHook: "shouldBlockCanBeCollidedWith",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceCanBeCollidedWith",
      replaceDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_return",
    },
    {
      srg: "m_6863_",
      mcp: "isPickable",
      desc: "()Z",
      type: "BOOLEAN",
      headHook: "shouldBlockIsPickable",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceIsPickable",
      replaceDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_return",
    },
    {
      srg: "m_20191_",
      mcp: "getBoundingBox",
      desc: "()Lnet/minecraft/world/phys/AABB;",
      type: "OBJECT",
      headHook: "shouldBlockGetBoundingBox",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceGetBoundingBox",
      replaceDesc: "(Ljava/lang/Object;)Lnet/minecraft/world/phys/AABB;",
      headStyle: "cancel_return",
    },
    {
      srg: "m_6469_",
      mcp: "hurt",
      desc: "(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
      type: "BOOLEAN",
      headHook: "shouldBlockHurt",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceHurt",
      replaceDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_return",
    },
    {
      srg: "m_21165_",
      mcp: "removeAllEffects",
      desc: "()Z",
      type: "BOOLEAN",
      headHook: "shouldBlockRemoveAllEffects",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceRemoveAllEffects",
      replaceDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_return",
    },
    {
      srg: "m_7832_",
      mcp: "shouldDropLoot",
      desc: "()Z",
      type: "BOOLEAN",
      headHook: "shouldBlockShouldDropLoot",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceShouldDropLoot",
      replaceDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_return",
    },
    {
      srg: "m_6085_",
      mcp: "shouldDropExperience",
      desc: "()Z",
      type: "BOOLEAN",
      headHook: "shouldBlockShouldDropExperience",
      headDesc: "(Ljava/lang/Object;)Z",
      replaceHook: "replaceShouldDropExperience",
      replaceDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_return",
    },
    {
      srg: "m_7967_",
      mcp: "addFreshEntity",
      desc: "(Lnet/minecraft/world/entity/Entity;)Z",
      type: "BOOLEAN",
      headHook: "shouldBlockAddFreshEntity",
      headDesc: "(Ljava/lang/Object;Ljava/lang/Object;)Z",
      replaceHook: "replaceHurt",
      replaceDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_return",
    },

    {
      srg: "m_8119_",
      mcp: "tick",
      desc: "()V",
      type: "VOID",
      headHook: "shouldBlockLivingTick",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_20124_",
      mcp: "setPose",
      desc: "(Lnet/minecraft/world/entity/Pose;)V",
      type: "VOID",
      headHook: "shouldBlockSetPose",
      headDesc: "(Ljava/lang/Object;Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_142687_",
      mcp: "setRemoved",
      desc: "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V",
      type: "VOID",
      headHook: "shouldBlockSetRemoved",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_6074_",
      mcp: "kill",
      desc: "()V",
      type: "VOID",
      headHook: "shouldBlockKill",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_142036_",
      mcp: "discard",
      desc: "()V",
      type: "VOID",
      headHook: "shouldBlockDiscard",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_142467_",
      mcp: "remove",
      desc: "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V",
      type: "VOID",
      headHook: "shouldBlockRemove",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_6091_",
      mcp: "move",
      desc: "(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
      type: "VOID",
      headHook: "shouldBlockMove",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_20344_",
      mcp: "setPosRaw",
      desc: "(DDD)V",
      type: "VOID",
      headHook: "shouldBlockSetPosRaw",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_20257_",
      mcp: "setDeltaMovement",
      desc: "(Lnet/minecraft/world/phys/Vec3;)V",
      type: "VOID",
      headHook: "shouldBlockSetDeltaMovement",
      headDesc: "(Ljava/lang/Object;Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_5765_",
      mcp: "push",
      desc: "(DDD)V",
      type: "VOID",
      headHook: "shouldBlockPush",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_6667_",
      mcp: "die",
      desc: "(Lnet/minecraft/world/damagesource/DamageSource;)V",
      type: "VOID",
      headHook: "shouldBlockDie",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_21154_",
      mcp: "setHealth",
      desc: "(F)V",
      type: "VOID",
      headHook: "shouldBlockSetHealth",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_21230_",
      mcp: "tickDeath",
      desc: "()V",
      type: "VOID",
      headHook: "shouldBlockTickDeath",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_6550_",
      mcp: "actuallyHurt",
      desc: "(Lnet/minecraft/world/damagesource/DamageSource;F)V",
      type: "VOID",
      headHook: "shouldBlockActuallyHurt",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_6660_",
      mcp: "knockback",
      desc: "(DDD)V",
      type: "VOID",
      headHook: "shouldBlockKnockback",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },
    {
      srg: "m_20259_",
      mcp: "setNoGravity",
      desc: "(Z)V",
      type: "VOID",
      headHook: "shouldBlockSetNoGravity",
      headDesc: "(Ljava/lang/Object;)Z",
      headStyle: "cancel_void",
    },

    {
      srg: "m_6075_",
      mcp: "baseTick",
      desc: "()V",
      type: "VOID",
      headHook: "onBaseTick",
      headDesc: "(Ljava/lang/Object;)V",
      headStyle: "no_cancel",
    },
  ];

  function injectHead(methodNode, mapping) {
    var list = new InsnList();

    if (mapping.headStyle === "no_cancel") {
      list.add(new VarInsnNode(Opcodes.ALOAD, 0));
      list.add(
        new MethodInsnNode(
          Opcodes.INVOKESTATIC,
          HOOKS,
          mapping.headHook,
          mapping.headDesc,
          false,
        ),
      );
    } else if (mapping.headStyle === "cancel_void") {
      list.add(new VarInsnNode(Opcodes.ALOAD, 0));
      if (mapping.headDesc === "(Ljava/lang/Object;Ljava/lang/Object;)Z") {
        list.add(new VarInsnNode(Opcodes.ALOAD, 1));
      }
      var label = new LabelNode();
      list.add(
        new MethodInsnNode(
          Opcodes.INVOKESTATIC,
          HOOKS,
          mapping.headHook,
          mapping.headDesc,
          false,
        ),
      );
      list.add(new JumpInsnNode(Opcodes.IFEQ, label));
      list.add(new InsnNode(Opcodes.RETURN));
      list.add(label);
    } else if (mapping.headStyle === "cancel_return") {
      list.add(new VarInsnNode(Opcodes.ALOAD, 0));
      if (mapping.headDesc === "(Ljava/lang/Object;Ljava/lang/Object;)Z") {
        list.add(new VarInsnNode(Opcodes.ALOAD, 1));
      }
      var label = new LabelNode();
      list.add(
        new MethodInsnNode(
          Opcodes.INVOKESTATIC,
          HOOKS,
          mapping.headHook,
          mapping.headDesc,
          false,
        ),
      );
      list.add(new JumpInsnNode(Opcodes.IFEQ, label));
      list.add(new VarInsnNode(Opcodes.ALOAD, 0));
      list.add(
        new MethodInsnNode(
          Opcodes.INVOKESTATIC,
          HOOKS,
          mapping.replaceHook,
          mapping.replaceDesc,
          false,
        ),
      );
      if (mapping.type === "BOOLEAN" || mapping.type === "INT") {
        list.add(new InsnNode(Opcodes.IRETURN));
      } else if (mapping.type === "FLOAT") {
        list.add(new InsnNode(Opcodes.FRETURN));
      } else if (mapping.type === "DOUBLE") {
        list.add(new InsnNode(Opcodes.DRETURN));
      } else {
        list.add(new InsnNode(Opcodes.ARETURN));
      }
      list.add(label);
    }

    methodNode.instructions.insert(list);
  }

  function transformClass(classNode) {
    var methods = classNode.methods;
    for (var i = 0; i < methods.size(); i++) {
      var method = methods.get(i);
      for (var j = 0; j < MAPPINGS.length; j++) {
        var m = MAPPINGS[j];
        if (
          (method.name === m.srg || method.name === m.mcp) &&
          method.desc === m.desc
        ) {
          if ((method.access & Opcodes.ACC_ABSTRACT) !== 0) continue;
          if ((method.access & Opcodes.ACC_NATIVE) !== 0) continue;
          injectHead(method, m);
          ASMAPI.log(
            "DEBUG",
            "[LAL Coremod] Injected hook into " +
              classNode.name +
              "." +
              method.name +
              method.desc,
          );
        }
      }
    }
    return classNode;
  }

  return {
    lal_entity: {
      target: { type: "CLASS", name: "net.minecraft.world.entity.Entity" },
      transformer: function (classNode) {
        return transformClass(classNode);
      },
    },
    lal_living_entity: {
      target: {
        type: "CLASS",
        name: "net.minecraft.world.entity.LivingEntity",
      },
      transformer: function (classNode) {
        return transformClass(classNode);
      },
    },
    lal_player: {
      target: {
        type: "CLASS",
        name: "net.minecraft.world.entity.player.Player",
      },
      transformer: function (classNode) {
        return transformClass(classNode);
      },
    },
    lal_server_player: {
      target: {
        type: "CLASS",
        name: "net.minecraft.server.level.ServerPlayer",
      },
      transformer: function (classNode) {
        return transformClass(classNode);
      },
    },
    lal_server_level: {
      target: { type: "CLASS", name: "net.minecraft.server.level.ServerLevel" },
      transformer: function (classNode) {
        return transformClass(classNode);
      },
    },
    lal_mob: {
      target: { type: "CLASS", name: "net.minecraft.world.entity.Mob" },
      transformer: function (classNode) {
        return transformClass(classNode);
      },
    },
  };
}
