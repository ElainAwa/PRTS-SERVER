package io.izzel.arclight.common.mixin.optimization.general.servercore.compat.superbwarfare;

import com.atsuishio.superbwarfare.entity.projectile.IFastMotionSync;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * SBW §2.5：高速弹体运动同步降频。
 *
 * 模组 {@code IFastMotionSync.syncMotionInterval()} 默认返回 -1（每 tick 向所有追踪
 * 玩家发送 ClientMotionSyncMessage）——1000 发在飞 × 20 追踪 ≈ 12 万包/s 量级。
 * 覆盖默认间隔为 3 tick：发送逻辑（syncMotion 默认实现）在 interval &gt; 0 时按
 * {@code tickCount % interval == 0} 节流，仍走原路径发送，仅频率降为 1/3。
 *
 * 语义核对：客户端运动插值由 ClientMotionSyncMessage 消费端负责，3 tick（150ms）
 * 间隔对高速弹体仍在插值可补偿范围；事件类型与协议不变。
 */
@LoadIfMod(modid = "superbwarfare", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = IFastMotionSync.class, remap = false)
public interface FastMotionSyncMixin_Sbw {

    /**
     * @reason 引擎侧运动同步降频（S2.11 §2.5）：默认每 tick → 每 3 tick。
     * @author Arclight
     */
    @Overwrite
    default int syncMotionInterval() {
        return 3;
    }
}
