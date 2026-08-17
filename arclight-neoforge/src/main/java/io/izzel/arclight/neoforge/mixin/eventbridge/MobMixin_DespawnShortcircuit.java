package io.izzel.arclight.neoforge.mixin.eventbridge;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.eventbridge.EventShortcircuitStats;
import io.izzel.arclight.neoforge.mod.event.EventBusQuery;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * P2-3 MobDespawnEvent short-circuit: {@code Mob.checkDespawn} fires the despawn event for
 * every despawn check (first statement of the method, bytecode-verified). With zero listeners
 * the event result is DEFAULT and the hook returns false — the vanilla despawn logic then runs
 * unchanged. The short-circuit returns that exact default, skipping construction + dispatch.
 * When a listener exists the original hook runs untouched.
 */
@Mixin(Mob.class)
public abstract class MobMixin_DespawnShortcircuit {

    @Redirect(method = "checkDespawn",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/neoforged/neoforge/event/EventHooks;checkMobDespawn(Lnet/minecraft/world/entity/Mob;)Z"))
    private static boolean arclight$checkMobDespawn(Mob mob) {
        if (PRTSFeaturesConfig.eventShortcircuitMobSpawnEnabled
                && !EventBusQuery.hasListeners(MobDespawnEvent.class)) {
            EventShortcircuitStats.increment("despawnSkipped");
            // MobDespawnEvent Result.DEFAULT -> hook returns false -> vanilla despawn continues.
            return false;
        }
        EventShortcircuitStats.increment("despawnForwarded");
        return EventHooks.checkMobDespawn(mob);
    }
}
