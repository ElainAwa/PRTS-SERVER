/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.async_pathfinding;

import io.izzel.arclight.common.optimization.general.servercore.ImmutablePathNavigationRegion;
import io.izzel.arclight.common.optimization.general.servercore.PathNavigationRegionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Immutable pathfinding snapshot factory. Exposes
 * {@link PathNavigationRegionAccess#arclight$snapshot}, which captures the block
 * states of this region into a flat immutable array on the main thread so the
 * async worker never touches live world state.
 */
@Mixin(PathNavigationRegion.class)
public abstract class PathNavigationRegionMixin implements PathNavigationRegionAccess {

    @Shadow
    @Final
    protected int centerX;

    @Shadow
    @Final
    protected int centerZ;

    @Shadow
    @Final
    protected ChunkAccess[][] chunks;

    @Shadow
    @Final
    protected Level level;

    @Shadow
    public abstract BlockState getBlockState(BlockPos pos);

    @Override
    public ImmutablePathNavigationRegion arclight$snapshot(int centerY, int yRadius) {
        int cx = this.chunks.length;
        int cz = this.chunks[0].length;
        // centerX/centerZ 是构造器 from 角(最小)的 section 坐标, 不是中心:
        // 直接 <<4 得最小方块坐标, 否则快照内容整体偏移导致 A* 全 1 节点路径。
        int minX = this.centerX << 4;
        int minZ = this.centerZ << 4;
        int maxX = minX + (cx << 4);
        int maxZ = minZ + (cz << 4);
        int xSize = maxX - minX;
        int zSize = maxZ - minZ;

        int minBuildHeight = this.level.getMinBuildHeight();
        int maxBuildHeight = this.level.getHeight();
        int yMin = Math.max(minBuildHeight, centerY - yRadius);
        int yMax = Math.min(maxBuildHeight, centerY + yRadius + 1);
        int ySize = Math.max(0, yMax - yMin);
        if (ySize == 0) ySize = 1;

        // 按 chunk 粒度遍历: 未加载/空 chunk 跳过(对应区域保持 null→空气),
        // 绝不在遍历中触发 EmptyLevelChunk 构造或 chunk 加载(registry 解析极重/可死循环)。
        BlockState[] states = new BlockState[xSize * ySize * zSize];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int czIdx = 0; czIdx < cz; czIdx++) {
            for (int cxIdx = 0; cxIdx < cx; cxIdx++) {
                ChunkAccess c = this.chunks[cxIdx][czIdx];
                if (c == null || c instanceof net.minecraft.world.level.chunk.EmptyLevelChunk) continue;
                int chunkMinX = minX + (cxIdx << 4);
                int chunkMinZ = minZ + (czIdx << 4);
                int x0 = Math.max(chunkMinX, minX);
                int x1 = Math.min(chunkMinX + 16, maxX);
                int z0 = Math.max(chunkMinZ, minZ);
                int z1 = Math.min(chunkMinZ + 16, maxZ);
                for (int z = z0; z < z1; z++) {
                    for (int x = x0; x < x1; x++) {
                        for (int y = yMin; y < yMax; y++) {
                            pos.set(x, y, z);
                            int idx = ((z - minZ) * ySize + (y - yMin)) * xSize + (x - minX);
                            states[idx] = c.getBlockState(pos);
                        }
                    }
                }
            }
        }

        WorldBorder snapshot = new WorldBorder();
        WorldBorder src = this.level.getWorldBorder();
        snapshot.setCenter(src.getCenterX(), src.getCenterZ());
        snapshot.setSize(src.getSize());

        return new ImmutablePathNavigationRegion(this.level,
                new BlockPos(minX, yMin, minZ), new BlockPos(maxX - 1, yMax - 1, maxZ - 1),
                states, xSize, ySize, zSize, minBuildHeight, maxBuildHeight, snapshot);
    }
}
