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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create 的方块实体首次 tick 在 region worker 上初始化机械网络时可能读到
 * 未就绪的 network 引用（Create 假设单线程时序）——首次 tick 排到主线程执行，
 * 之后 initialized 置位，后续 tick 仍由 region worker 并行处理。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = SmartBlockEntity.class, remap = false)
public abstract class SmartBlockEntityMixin_MainThreadInit {

    @Shadow(remap = false)
    private boolean initialized;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void arclight$firstTickOnMain(CallbackInfo ci) {
        if (!this.initialized && RegionTickManager.isRegionWorker()) {
            RegionTickManager.queueMainThreadBlockEntity((BlockEntity) (Object) this);
            ci.cancel();
        }
    }
}
