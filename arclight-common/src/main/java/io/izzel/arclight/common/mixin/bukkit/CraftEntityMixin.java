package io.izzel.arclight.common.mixin.bukkit;

import io.izzel.arclight.common.mod.server.entity.EntityClassLookup;
import java.util.function.BiFunction;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.entity.CraftEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftEntity.class, remap = false)
public abstract class CraftEntityMixin implements org.bukkit.entity.Entity {

    @Shadow protected Entity entity;
    @Shadow @Final protected CraftServer server;

    // 类型表误命中 vanilla 映射时 convert 强转会抛 CCE，捕获后回退到按类解析的 EntityClassLookup（对任意自定义实体返回通用 CraftEntity）
    @Redirect(method = "getEntity", at = @At(value = "INVOKE", target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object arclight$safeConvert(BiFunction<Object, Object, Object> convert, Object server, Object nmsEntity) {
        try {
            return convert.apply(server, nmsEntity);
        } catch (ClassCastException ex) {
            Entity entity = (Entity) nmsEntity;
            return EntityClassLookup.getEntityTypeData(entity).convertFunction().apply((CraftServer) server, entity);
        }
    }

    @Inject(method = "getEntity", cancellable = true, at = @At(value = "NEW", target = "java/lang/AssertionError"))
    private static void arclight$modEntity(CraftServer server, Entity entity, CallbackInfoReturnable<CraftEntity> cir) {
        var craftEntity = (CraftEntity) EntityClassLookup.getEntityTypeData(entity).convertFunction().apply(server, entity);
        cir.setReturnValue(craftEntity);
    }
}
