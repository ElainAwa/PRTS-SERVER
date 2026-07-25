package io.izzel.arclight.common.mixin.optimization.general.nearbyplayers;

import io.izzel.arclight.common.optimization.general.nearbyplayers.NearbyPlayerIndex;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NearbyPlayerIndex 查询侧 #1：接管 Mob.checkDespawn 中的无界 getNearestPlayer。
 * 1.21.1 字节码确认（javap Mob.class）：INVOKE 属主为 Level（invokevirtual
 * Level.getNearestPlayer:(Lnet/minecraft/world/entity/Entity;D)LPlayer;），与 1.20.1 一致。
 * 与 core MobMixin 的 @Inject(checkDespawn, at=INVOKE discard()) 目标不同，可共存。
 * 索引不可用/桶未命中/超守卫 → 原样调用 vanilla，行为零变更。
 */
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
    private Player luminara$npiNearestPlayer(Level level, Entity entity, double maxDist) {
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
