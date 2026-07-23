package io.izzel.arclight.common.mixin.optimization.general.nearbyplayers;

import io.izzel.arclight.common.optimization.general.nearbyplayers.NearbyPlayerIndex;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NearbyPlayerIndex 查询侧 #2：接管 BaseSpawner.isNearPlayer 中的 hasNearbyAlivePlayer。
 * 字节码确认（javap BaseSpawner.class）：INVOKE 属主为 Level（invokevirtual
 * Level.hasNearbyAlivePlayer:(DDDD)Z）。刷怪笼默认 requiredPlayerRange=16 ≤ 守卫 144，
 * 桶即全集 ⇒ 确定性答案；range 越界或索引不可用 → 原样调用 vanilla。
 * 注：core 的 BaseSpawnerMixin 仅 @Overwrite serverTick（仍经 this.isNearPlayer），无撞车。
 */
@Mixin(BaseSpawner.class)
public abstract class MixinBaseSpawner_NearbyIndex {

    @Redirect(
        method = "isNearPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;hasNearbyAlivePlayer(DDDD)Z"
        ),
        require = 1
    )
    private boolean luminara$npiHasNearbyAlivePlayer(Level level, double x, double y, double z, double range) {
        if (!NearbyPlayerIndex.enabled()) {
            return level.hasNearbyAlivePlayer(x, y, z, range);
        }
        NearbyPlayerIndex index = NearbyPlayerIndex.of(level);
        if (index == null) {
            return level.hasNearbyAlivePlayer(x, y, z, range);
        }
        return index.hasNearbyAlivePlayer(level, x, y, z, range);
    }
}
