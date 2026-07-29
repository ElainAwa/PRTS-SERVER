package io.izzel.arclight.common.mixin.optimization.general.nearbyplayers;

import io.izzel.arclight.common.optimization.general.nearbyplayers.NearbyPlayerIndex;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** NearbyPlayerIndex 查询侧 #1：接管 Mob.checkDespawn 中的无界 getNearestPlayer。 */
@Mixin(Mob.class)
public abstract class MixinMob_NearbyIndex {

    @Redirect(
        method = "checkDespawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"
        ),
        require = 1
    )
    private Player prts$npiNearestPlayer(Level level, Entity entity, double maxDist) {
        if (!NearbyPlayerIndex.enabled()) {
            return level.getNearestPlayer(entity, maxDist);
        }
        NearbyPlayerIndex index = NearbyPlayerIndex.of(level);
        if (index == null) {
            return level.getNearestPlayer(entity, maxDist);
        }
        return index.getNearestPlayer(level, entity, maxDist);
    }
}
