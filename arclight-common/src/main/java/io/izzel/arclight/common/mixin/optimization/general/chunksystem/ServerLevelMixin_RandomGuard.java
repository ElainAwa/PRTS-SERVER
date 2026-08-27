/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.bridge.optimization.ILevelRandomAccess;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.chunksystem.guards.LevelRandomGuard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code World.random} 跨线程检测装配（M2.2 四件套 ④）：构造完成后把
 * {@code Level.random} 替换为 {@link LevelRandomGuard} 装饰器。
 *
 * <p>挂 {@code ServerLevel.<init>} 而非 {@code Level.<init>}：后者 RETURN
 * 时 {@code getServer()} 仍为 null（{@code this.server} 由子类随后赋值），
 * 无法取得服务端上下文；构造注入需全参数签名（对齐 core 层
 * {@code ServerLevelMixin.arclight$init}）。{@code random} 字段声明在
 * {@code Level}，重赋值经 {@link ILevelRandomAccess} 桥（目标为 Level 的
 * {@code LevelMixin_RandomGuardField}）；装饰器默认 {@code warn} 模式仅收集
 * 违规线程清单（灰度后由配置切 {@code throw}）。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_RandomGuard {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSystem");
    private static volatile boolean announced;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void prts$installRandomGuard(MinecraftServer minecraftServer, java.util.concurrent.Executor backgroundExecutor,
            LevelStorageSource.LevelStorageAccess levelSave, ServerLevelData worldInfo, ResourceKey<Level> dimension,
            LevelStem levelStem, ChunkProgressListener statusListener, boolean isDebug, long seed,
            java.util.List<CustomSpawner> specialSpawners, boolean shouldBeTicking,
            RandomSequences seq, CallbackInfo ci) {
        if (!PRTSFeaturesConfig.chunkSystemEnabled
                || !"warn".equals(PRTSFeaturesConfig.worldgenRandomCheck)
                && !"throw".equals(PRTSFeaturesConfig.worldgenRandomCheck)) {
            return;
        }
        ((ILevelRandomAccess) this).prts$setRandom(
                new LevelRandomGuard(((ServerLevel) (Object) this).random, minecraftServer));
        if (!announced) {
            announced = true;
            LOGGER.info("[chunk-system] World.random cross-thread guard installed (mode={})",
                    PRTSFeaturesConfig.worldgenRandomCheck);
        }
    }
}
