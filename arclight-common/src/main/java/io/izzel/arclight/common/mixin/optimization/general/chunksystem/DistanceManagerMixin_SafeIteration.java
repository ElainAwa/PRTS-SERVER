/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;
import java.util.function.Consumer;

/**
 * worldgen 线程安全包（M3，照搬清单 6）：{@code DistanceManager}（TicketManager）
 * 迭代安全。
 *
 * <p>原版 {@code runAllUpdates} 对 {@code chunksToUpdateFutures}（裸 HashSet）
 * 直接 {@code forEach}；{@code updateFutures} 推进链上若同步触发票据变化
 * （holder 重新入集），即 {@code ConcurrentModificationException}。
 * 照搬 C2ME 口径改快照数组迭代（{@code toArray} 后逐个消费），
 * 迭代中新增的 holder 留待下一轮 update——与原版「本轮已枚举集合」语义一致。
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin_SafeIteration {

    @Redirect(method = "runAllUpdates",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;forEach(Ljava/util/function/Consumer;)V"))
    private void prts$snapshotIteration(Set<ChunkHolder> holders, Consumer<ChunkHolder> action) {
        for (ChunkHolder holder : holders.toArray(new ChunkHolder[0])) {
            action.accept(holder);
        }
    }
}
