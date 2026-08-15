/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Reusable task-level read-only block view: a flat BlockState array captured on
 * the owning thread, used by workers for pathfinding, shape checks and other
 * pure-read hotspots. BlockEntity reads return null by design — the snapshot is
 * block-state only, never a live world view.
 */
public class ImmutableBlockView implements BlockGetter {

    private final BlockState[] states;
    private final int xSize;
    private final int ySize;
    private final int zSize;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int minBuildHeight;
    private final int maxBuildHeight;

    public ImmutableBlockView(BlockState[] states, int xSize, int ySize, int zSize,
                              int minX, int minY, int minZ,
                              int minBuildHeight, int maxBuildHeight) {
        this.states = states;
        this.xSize = xSize;
        this.ySize = ySize;
        this.zSize = zSize;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.minBuildHeight = minBuildHeight;
        this.maxBuildHeight = maxBuildHeight;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX() - this.minX;
        int y = pos.getY() - this.minY;
        int z = pos.getZ() - this.minZ;
        if (x < 0 || y < 0 || z < 0 || x >= this.xSize || y >= this.ySize || z >= this.zSize) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = this.states[(z * this.ySize + y) * this.xSize + x];
        return state != null ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return this.maxBuildHeight;
    }

    @Override
    public int getMinBuildHeight() {
        return this.minBuildHeight;
    }
}
