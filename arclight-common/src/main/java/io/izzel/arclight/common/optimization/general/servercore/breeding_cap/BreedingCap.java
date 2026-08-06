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

/**
 * 繁殖上限逻辑（移植自 Wesley1808/ServerCore BreedingCap）。
 * 按 range 内同类型实体计数判断超限；CUSTOM_TYPES 处理蛙→蝌蚪/蛙 的跨类型统计。
 */
public class BreedingCap {
    public static final Map<EntityType<?>, Set<EntityType<?>>> CUSTOM_TYPES = Map.of(
            EntityType.FROG, Set.of(EntityType.TADPOLE, EntityType.FROG)
    );

    private final int limit;
    private final int range;
    private final boolean unlimitedHeight;

    public BreedingCap(int limit, int range, boolean unlimitedHeight) {
        this.limit = limit;
        this.range = range;
        this.unlimitedHeight = unlimitedHeight;
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
            count = level.getEntities(type, area, EntitySelector.NO_SPECTATORS).size();
        }

        return limit <= count;
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
