/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create 漏斗吸物入口无 null 守卫：并行读取竞态下 BlockEntityBehaviour.get
 * 可能返回 null，原代码直接 filter.test 会 NPE 崩服。仅当 Create 加载时，
 * 行为缺失则跳过本次吸物（下一 tick 自然恢复），并兜底吞掉读取异常。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = FunnelBlock.class, remap = false)
public abstract class FunnelBlockMixin_Create {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true, remap = false)
    private void arclight$skipWhenBehaviourMissing(BlockState state, Level level, BlockPos pos, Entity entity, CallbackInfo ci) {
        try {
            if (BlockEntityBehaviour.get(level, pos, FilteringBehaviour.TYPE) == null) {
                ci.cancel();
            }
        } catch (Throwable t) {
            ci.cancel();
        }
    }
}
