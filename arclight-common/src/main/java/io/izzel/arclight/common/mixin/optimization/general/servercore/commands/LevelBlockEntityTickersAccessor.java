package io.izzel.arclight.common.mixin.optimization.general.servercore.commands;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

// blockEntityTickers 在 1.20.1 为 protected，跨包不可直接访问。
@Mixin(Level.class)
public interface LevelBlockEntityTickersAccessor {

    @Accessor("blockEntityTickers")
    List<TickingBlockEntity> arclight$blockEntityTickers();
}
