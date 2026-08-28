package io.izzel.arclight.neoforge.mixin.core.world.level.block;

import io.izzel.arclight.common.bridge.core.world.level.block.BlockBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.extensions.IBlockExtension;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Collections;
import java.util.List;

@Mixin(Block.class)
public abstract class BlockMixin_NeoForge implements BlockBridge, IBlockExtension {

    /**
     * S2.11 A/B 实测发现的 SBW 场景崩溃：neoforge 补丁的 dropResources 用
     * beginCapturingDrops/stopCapturingDrops 收集 ItemEntity 列表后调
     * CommonHooks.handleBlockDrops —— 捕获未配对（嵌套/线程时序）时
     * stopCapturingDrops 返回 null → BlockDropsEvent.getDrops() null → NPE 崩服
     * （SBW 弹道破坏方块即触发，基线/当前均复现）。本注入把 null 换成空列表：
     * 掉落物此时已由 forEach 真实生成，跳过事件派发语义正确；正常路径
     * （drops 非 null）完全不干预。
     */
    @ModifyArg(method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/neoforged/neoforge/common/CommonHooks;handleBlockDrops(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;Ljava/util/List;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V"),
            index = 4)
    private static List<ItemEntity> arclight$guardNullBlockDrops(List<ItemEntity> drops) {
        return drops == null ? Collections.emptyList() : drops;
    }

    @Override
    public boolean bridge$forge$onCropsGrowPre(Level level, BlockPos pos, BlockState state, boolean def) {
        return CommonHooks.canCropGrow(level, pos, state, def);
    }

    @Override
    public void bridge$forge$onCropsGrowPost(Level level, BlockPos pos, BlockState state) {
        CommonHooks.fireCropGrowPost(level, pos, state);
    }

    @Override
    public void bridge$forge$onCaughtFire(BlockState state, Level level, BlockPos pos, @Nullable Direction direction, @Nullable LivingEntity igniter) {
        this.onCaughtFire(state, level, pos, direction, igniter);
    }
}
