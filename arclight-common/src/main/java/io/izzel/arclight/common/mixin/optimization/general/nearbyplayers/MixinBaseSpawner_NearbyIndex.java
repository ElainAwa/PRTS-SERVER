package io.izzel.arclight.common.mixin.optimization.general.nearbyplayers;

import io.izzel.arclight.common.optimization.general.nearbyplayers.NearbyPlayerIndex;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** NearbyPlayerIndex 查询侧 #2：接管 BaseSpawner.isNearPlayer 中的 hasNearbyAlivePlayer。 */
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
