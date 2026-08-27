/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code ChunkStorage.storageInfo()} 的访问器。
 *
 * <p>必须直接挂在 {@link ChunkStorage} 上（而非子类 {@code ChunkMap}）：
 * 构建期 jar 重映射按注解所在目标类解析成员名，继承成员在子类上查不到
 * 映射，运行期按 Mojang 名定位即 {@code InvalidMixinException}。
 */
@Mixin(ChunkStorage.class)
public interface ChunkStorageAccessor_ChunkSystem {

    @Invoker("storageInfo")
    RegionStorageInfo prts$storageInfo();
}
