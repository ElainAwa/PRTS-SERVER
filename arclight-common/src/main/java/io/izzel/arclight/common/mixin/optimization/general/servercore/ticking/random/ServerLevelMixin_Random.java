package io.izzel.arclight.common.mixin.optimization.general.servercore.ticking.random;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.ticking.ILevelChunk;
import io.izzel.arclight.common.optimization.general.servercore.ticking.IServerLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ServerLevel.class, priority = 900)
public class ServerLevelMixin_Random implements IServerLevel {

    @Unique
    private int arclight$currentIceAndSnowTick = 0;

    // tickChunk 的第 1 处 nextInt 是雷暴判定，交给区块自持倒计时。
    @Redirect(
            method = "tickChunk",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/RandomSource;nextInt(I)I",
                    ordinal = 0
            )
    )
    private int arclight$replaceLightningCheck(RandomSource randomSource, int thunderChance, LevelChunk chunk, int randomTickSpeed) {
        if (!ServerCoreConfig.optimizations().optimizeChunkRandomTicks()) {
            return randomSource.nextInt(thunderChance);
        }
        return ((ILevelChunk) chunk).arclight$shouldDoLightning(randomSource, thunderChance);
    }

    // 第 2 处是结冰/积雪判定，改为每 tick 单次随机 + 逐区块自增。
    @Redirect(
            method = "tickChunk",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/RandomSource;nextInt(I)I",
                    ordinal = 1
            )
    )
    private int arclight$replaceIceAndSnowCheck(RandomSource randomSource, int i) {
        if (!ServerCoreConfig.optimizations().optimizeChunkRandomTicks()) {
            return randomSource.nextInt(i);
        }
        return this.arclight$currentIceAndSnowTick++ & 15;
    }

    @Override
    public void arclight$resetIceAndSnowTick() {
        this.arclight$currentIceAndSnowTick = ((ServerLevel) (Object) this).getRandom().nextInt(16);
    }
}
