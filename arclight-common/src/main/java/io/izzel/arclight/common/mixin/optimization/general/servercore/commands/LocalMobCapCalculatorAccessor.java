package io.izzel.arclight.common.mixin.optimization.general.servercore.commands;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 暴露 LocalMobCapCalculator.playerMobCounts（private final）。
 * 值类型 MobCounts 为包私有，此处以 Object 承接（擦除后同为 Ljava/util/Map;）。
 */
@Mixin(LocalMobCapCalculator.class)
public interface LocalMobCapCalculatorAccessor {

    @Accessor("playerMobCounts")
    Map<ServerPlayer, Object> arclight$playerMobCounts();
}
