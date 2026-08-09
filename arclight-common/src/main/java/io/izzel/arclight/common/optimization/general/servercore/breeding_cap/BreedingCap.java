/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.breeding_cap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 繁殖上限逻辑（移植自 Wesley1808/ServerCore BreedingCap）。
 * 按 range 内同类型实体计数判断超限；CUSTOM_TYPES 处理蛙→蝌蚪/蛙 的跨类型统计。
 * 计数缓存：同一 tick 内相同 (type, chunk) 的计数复用，避免每次繁殖判定全扫 128³。
 */
public class BreedingCap {
    public static final Map<EntityType<?>, Set<EntityType<?>>> CUSTOM_TYPES = Map.of(
            EntityType.FROG, Set.of(EntityType.TADPOLE, EntityType.FROG)
    );

    /** 缓存条目标记：记录统计时的 tick，跨 tick 自动失效。 */
    private static final class CacheEntry {
        final int count;
        final long tick;

        CacheEntry(int count, long tick) {
            this.count = count;
            this.tick = tick;
        }
    }

    /** 同 tick 内最多缓存条目数，超出即整体清空（防泄漏，chunk 数量极多时兜底）。 */
    private static final int CACHE_MAX_ENTRIES = 4096;

    /** key = (type, chunkX, chunkZ) 复合 key。 */
    private static final class CacheKey {
        final EntityType<?> type;
        final long chunkPos;

        CacheKey(EntityType<?> type, long chunkPos) {
            this.type = type;
            this.chunkPos = chunkPos;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey k = (CacheKey) o;
            return type == k.type && chunkPos == k.chunkPos;
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + Long.hashCode(chunkPos);
        }
    }

    private static final Map<CacheKey, CacheEntry> COUNT_CACHE = new ConcurrentHashMap<>();

    private final int limit;
    private final int range;
    private final boolean unlimitedHeight;

    public BreedingCap(int limit, int range, boolean unlimitedHeight) {
        this.limit = limit;
        this.range = range;
        this.unlimitedHeight = unlimitedHeight;
    }

    /** 仅在开启缓存时调用（ServerCoreConfig.breedingCap() != null 且启用）。 */
    public static void clearCache() {
        COUNT_CACHE.clear();
    }

    public boolean exceedsLimit(EntityType<?> type, Level level, BlockPos pos) {
        final int limit = this.limit;
        if (limit < 0) {
            return false;
        }

        AABB area = getAreaAt(level, pos);
        Set<EntityType<?>> set = CUSTOM_TYPES.get(type);

        int count;
        if (set != null && !set.isEmpty()) {
            count = level.getEntities((Entity) null, area, (entity) -> set.contains(entity.getType())).size();
        } else {
            count = countWithCache(type, level, area);
        }

        return limit <= count;
    }

    /**
     * 普通类型走 tick 级计数缓存：同 tick 同 chunk 复用扫描结果。
     * CUSTOM_TYPES 集合类型（蛙）不缓存——跨类型判定场景少、key 无法归并。
     */
    private int countWithCache(EntityType<?> type, Level level, AABB area) {
        long tick = level.getServer() == null ? -1L : level.getServer().getTickCount();
        if (tick < 0L) {
            return level.getEntities(type, area, EntitySelector.NO_SPECTATORS).size();
        }

        long chunkPos = chunkPosKey(area);
        CacheKey key = new CacheKey(type, chunkPos);
        CacheEntry cached = COUNT_CACHE.get(key);
        if (cached != null && cached.tick == tick) {
            return cached.count;
        }

        int count = level.getEntities(type, area, EntitySelector.NO_SPECTATORS).size();
        if (COUNT_CACHE.size() >= CACHE_MAX_ENTRIES) {
            COUNT_CACHE.clear();
        }
        COUNT_CACHE.put(key, new CacheEntry(count, tick));
        return count;
    }

    /** 按查询 AABB 的区块列归并（同列共享计数），避免 pos 微差导致 key 发散。 */
    private static long chunkPosKey(AABB area) {
        int cx = Math.floorDiv((int) Math.floor(area.minX), 16);
        int cz = Math.floorDiv((int) Math.floor(area.minZ), 16);
        return (long) cx << 32 | (cz & 0xffffffffL);
    }

    public boolean exceedsLimit(Entity entity) {
        return exceedsLimit(entity.getType(), entity.level(), entity.blockPosition());
    }

    private AABB getAreaAt(Level level, BlockPos pos) {
        final boolean unlimitedHeight = this.unlimitedHeight;
        final int range = this.range;
        final int minHeight = level.getMinBuildHeight();
        final int maxHeight = level.getMaxBuildHeight() + 4;

        final int minX = pos.getX() - range;
        final int minY = unlimitedHeight ? minHeight : Math.max(minHeight, pos.getY() - range);
        final int minZ = pos.getZ() - range;

        final int maxX = pos.getX() + range;
        final int maxY = unlimitedHeight ? maxHeight : Math.min(maxHeight, pos.getY() + range);
        final int maxZ = pos.getZ() + range;

        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    public static void resetLove(Animal owner, Animal mate) {
        resetAge(owner, mate);
        owner.resetLove();
        mate.resetLove();
    }

    public static void resetAge(AgeableMob owner, AgeableMob mate) {
        owner.setAge(6000);
        mate.setAge(6000);
    }
}
