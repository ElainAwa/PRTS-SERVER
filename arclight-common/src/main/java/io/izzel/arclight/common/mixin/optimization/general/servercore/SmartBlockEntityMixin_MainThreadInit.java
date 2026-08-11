/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create 的方块实体整个生命周期（机械网络初始化、网络验证、移除）都假设单线程
 * 时序，在 region worker 上并行 tick 会产生竞态（network 引用未就绪/失效）——
 * 全部排到主线程执行，不参与区域并行。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = SmartBlockEntity.class, remap = false)
public abstract class SmartBlockEntityMixin_MainThreadInit {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void arclight$tickOnMain(CallbackInfo ci) {
        if (RegionTickManager.isRegionWorker()) {
            RegionTickManager.queueMainThreadBlockEntity((BlockEntity) (Object) this);
            ci.cancel();
        }
    }
}
