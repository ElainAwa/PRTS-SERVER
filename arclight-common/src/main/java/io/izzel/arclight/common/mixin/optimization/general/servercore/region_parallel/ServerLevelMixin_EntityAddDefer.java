/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Worker-thread entity adds are deferred to the main thread.
 *
 * <p>Region workers tick mob AI whose {@code spawnAtLocation}/{@code addFreshEntity}
 * call {@code ServerLevel.addEntity} on the worker. Executing the add there is seen by
 * third-party guards (Cupboard ServerAddEntityMixin) as an offthread add and the entity
 * ends up registered twice — production 08-22 showed 237 "UUID of added entity already
 * exists" warnings in one afternoon (Chicken eggs, mob drops, XP orbs, scguns projectiles,
 * OminousItemSpawner, ...). The add must happen exactly once, on the main thread, so
 * worker {@code addFreshEntity} calls are cancelled at HEAD and queued via
 * {@link RegionTickManager#queueMainThreadEntityAdd}, then applied by the main thread
 * after the region/dimension barrier.</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_EntityAddDefer {

    // 注入 addFreshEntity（public 入口）而不是 addEntity：
    // addFreshEntity 先于 addEntity 执行，天然先于 Cupboard ServerAddEntityMixin
    // 对 addEntity 的 HEAD 注入。Cupboard 在 offthread 路径 setReturnValue(true)
    // （Mixin 语义=隐式 cancel）会跳过排在它之后的注入与原方法体；反过来我们在
    // 外层先 cancel，Cupboard 根本看不到这次 offthread add，不会进它的 toAdd
    // 补加队列——正是生产 "UUID of added entity already exists" 的第二条来源。
    @Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void arclight$deferWorkerEntityAdd(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        // 主线程自身/vanilla 路径直接放行；仅并行 worker 上的新增需要排队。
        // 实体 level 尚未设置的异常调用也放行，交给原路径处理（保持 vanilla 报错语义）。
        if ((RegionTickManager.isRegionWorker() || DimensionTickManager.isDimensionTickThread())
                && entity.level() instanceof ServerLevel) {
            RegionTickManager.queueMainThreadEntityAdd(entity);
            // setReturnValue 隐含 cancel：worker 上原 addFreshEntity 体不再执行，
            // 乐观返回 true；真实落地由主线程 drain 时的 addFreshEntity 完成。
            cir.setReturnValue(true);
        }
    }
}
