package io.izzel.arclight.common.mixin.optimization.general.servercore.commands;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 LocalMobCapCalculator$MobCounts.counts；该内部类在 1.20.1 为包私有，只能用 targets 指定。
 */
@Mixin(targets = "net.minecraft.world.level.LocalMobCapCalculator$MobCounts")
public interface MobCountsAccessor {

    @Accessor("counts")
    Object2IntMap<MobCategory> arclight$counts();
}
