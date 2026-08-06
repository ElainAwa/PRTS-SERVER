/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.ticking.broadcast;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.ticking.IServerChunkCache;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.function.Consumer;

@Mixin(value = ServerChunkCache.class, priority = 900)
public class ServerChunkCacheMixin_Broadcast implements IServerChunkCache {

    @Unique
    private final ReferenceLinkedOpenHashSet<ChunkHolder> arclight$requiresBroadcast = new ReferenceLinkedOpenHashSet<>(128);

    // 仅当下方 @Redirect 真正生效（require=0 可能静默失配）后才收集，否则集合永不清空会泄漏。
    // active 由 ChunkHolder 注入点置位：注入点失效 ⇒ active 恒 false ⇒ Redirect 恒回退原版，杜绝广播短路。
    @Unique
    private boolean arclight$broadcastActive = false;

    // ChunkAndHolder 为包私有，签名用通配泛型靠擦除匹配。
    @Redirect(
            method = "tickChunks",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V",
                    ordinal = 0
            )
    )
    private void arclight$broadcastChanges(List<?> list, Consumer consumer) {
        if (!ServerCoreConfig.optimizations().optimizeChunkBroadcasts()) {
            this.arclight$broadcastActive = false;
            this.arclight$requiresBroadcast.clear();
            list.forEach(consumer);
            return;
        }
        if (!this.arclight$broadcastActive) {
            // 注入点尚未生效（本 tick 无变更）→ 仍走原版广播，避免丢包
            list.forEach(consumer);
            return;
        }
        for (ChunkHolder holder : this.arclight$requiresBroadcast) {
            LevelChunk chunk = holder.getTickingChunk();
            if (chunk != null) {
                holder.broadcastChanges(chunk);
            }
        }
        this.arclight$requiresBroadcast.clear();
    }

    @Override
    public void arclight$requiresBroadcast(ChunkHolder holder) {
        if (!ServerCoreConfig.optimizations().optimizeChunkBroadcasts()) return;
        this.arclight$broadcastActive = true;
        this.arclight$requiresBroadcast.add(holder);
    }
}
