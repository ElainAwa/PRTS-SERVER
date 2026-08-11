/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Immutable pathfinding snapshot: a read-only copy of the block states a task
 * needs, captured on the server thread when the task is submitted (BlockState
 * instances are immutable singletons, so copying references is safe). All readable
 * methods are overridden so the worker never touches live world state.
 */
public final class ImmutablePathNavigationRegion extends PathNavigationRegion {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final ProfilerFiller NOOP_PROFILER = new NoopProfiler();

    private final BlockState[] states;
    private final int xSize;
    private final int ySize;
    private final int zSize;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int minBuildHeight;
    private final int maxBuildHeight;
    private final WorldBorder borderSnapshot;

    public ImmutablePathNavigationRegion(Level level, BlockPos minPos, BlockPos maxPos,
                                         BlockState[] states, int xSize, int ySize, int zSize,
                                         int minBuildHeight, int maxBuildHeight, WorldBorder borderSnapshot) {
        super(level, minPos, maxPos);
        this.states = states;
        this.xSize = xSize;
        this.ySize = ySize;
        this.zSize = zSize;
        this.minX = minPos.getX();
        this.minY = minPos.getY();
        this.minZ = minPos.getZ();
        this.minBuildHeight = minBuildHeight;
        this.maxBuildHeight = maxBuildHeight;
        this.borderSnapshot = borderSnapshot;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX() - this.minX;
        int y = pos.getY() - this.minY;
        int z = pos.getZ() - this.minZ;
        if (x < 0 || y < 0 || z < 0 || x >= this.xSize || y >= this.ySize || z >= this.zSize) {
            return AIR;
        }
        BlockState state = this.states[(z * this.ySize + y) * this.xSize + x];
        return state != null ? state : AIR;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public int getMinBuildHeight() {
        return this.minBuildHeight;
    }

    @Override
    public int getHeight() {
        return this.maxBuildHeight;
    }

    @Override
    public WorldBorder getWorldBorder() {
        return this.borderSnapshot;
    }

    @Override
    public ProfilerFiller getProfiler() {
        return NOOP_PROFILER;
    }

    @Override
    public BlockGetter getChunkForCollisions(int x, int z) {
        return this;
    }

    @Override
    public List<VoxelShape> getEntityCollisions(Entity entity, AABB aabb) {
        return List.of();
    }

    private static final class NoopProfiler implements ProfilerFiller {
        @Override public void startTick() { }
        @Override public void endTick() { }
        @Override public void push(String name) { }
        @Override public void push(java.util.function.Supplier<String> nameSupplier) { }
        @Override public void pop() { }
        @Override public void popPush(String name) { }
        @Override public void popPush(java.util.function.Supplier<String> nameSupplier) { }
        @Override public void markForCharting(net.minecraft.util.profiling.metrics.MetricCategory category) { }
        @Override public void incrementCounter(String name, int increment) { }
        @Override public void incrementCounter(java.util.function.Supplier<String> nameSupplier, int increment) { }
    }
}
