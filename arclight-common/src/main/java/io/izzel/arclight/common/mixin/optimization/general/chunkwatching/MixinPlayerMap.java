package io.izzel.arclight.common.mixin.optimization.general.chunkwatching;

import io.izzel.arclight.common.optimization.general.chunkwatching.IChunkWatchingManager;
import net.minecraft.server.level.PlayerMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * chunkwatching (子系统 B) — 已回退到 vanilla 语义（2026-07-23 实锤 bug 后）。
 *
 * <p>根本原因：Forge 1.20.1 的 {@link PlayerMap} 结构与 HariPlayer（VMP fork）假设的"新版 MC
 * chunkPos→Set 索引"完全不同。1.20.1 的 PlayerMap 是 {@code player -> watchDisabled flag} 的
 * 单值映射，{@code getPlayers(long)} 忽略参数、返回 {@code players.keySet()}（所有玩家），
 * 然后由 {@code PlayerChunkMap.getPlayers(ChunkCoordIntPair, boolean)} 按 {@code this.viewDistance}
 * 逐玩家距离过滤来决定区块发送。
 *
 * <p>原先的 {@code @Overwrite getPlayers} 返回 AreaMap 维护的"视距覆盖某区块的玩家"子集，与上面的
 * 契约冲突：当 AreaMap 维护的 watchDistance 与服务器实际 viewDistance 不一致、或玩家跨越中心区块
 * 边界触发 AreaMap 重算而遗漏边缘区块时，{@code PlayerChunkMap.getPlayers} 遍历到的集合不完整，
 * 导致部分远端区块不被发送 —— 客户端表现为黑色未加载区块（退出重进重置 AreaMap 状态后暂时正常，
 * 随后随移动累积误差复现）。
 *
 * <p>修复：移除 {@code @Overwrite getPlayers}，恢复 vanilla 的"返回所有玩家"语义，区块加载/发送
 * 回到 100% vanilla。AreaMap 空间索引在此 PlayerMap 结构上本就建错，停用以避免无谓开销与潜在污染。
 * 本 mixin 仅保留 active 标记与 {@link IChunkWatchingManager} 接口实现，待基于 1.20.1 真实
 * PlayerMap 结构（player→flag + 距离过滤）重新设计 B 组（空间最近玩家查找）时再用。
 */
@Mixin(PlayerMap.class)
public abstract class MixinPlayerMap implements IChunkWatchingManager {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-CW");
    private static boolean logged = false;
    private int watchDistance = 5;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onConstruct(CallbackInfo ci) {
        if (!logged) {
            logged = true;
            LOGGER.info("[PRTS-CW] chunkwatching mixin active (spatial index disabled — using vanilla getPlayers)");
        }
    }

    @Override
    public void setWatchDistance(int watchDistance) {
        this.watchDistance = Math.max(3, watchDistance);
    }

    @Override
    public int getWatchDistance() {
        return this.watchDistance;
    }
}
