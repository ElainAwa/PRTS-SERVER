package io.izzel.arclight.common.mixin.optimization.general.servercore.commands;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * 暴露 LocalMobCapCalculator.getPlayersNear（private）。
 * mob_spawning 的 Mobcaps 判定需要遍历临近玩家的局部计数。
 */
@Mixin(LocalMobCapCalculator.class)
public interface LocalMobCapCalculatorInvoker {

    @Invoker("getPlayersNear")
    List<ServerPlayer> arclight$getPlayersNear(ChunkPos pos);
}
