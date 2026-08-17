package io.izzel.arclight.neoforge.mixin.eventbridge;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.eventbridge.EventShortcircuitStats;
import io.izzel.arclight.neoforge.mod.event.EventBusQuery;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * P1-3 EntityTickEvent short-circuit (plan 2026-08-17 §8.5): the highest-frequency
 * event family — Pre+Post per entity per tick — fired only from
 * {@code ServerLevel.tickNonPassenger} (bytecode-verified, the single call site).
 *
 * <p>With zero bus listeners the Pre is never cancelled, so returning the cached
 * default instance (entity = null, nobody reads the field) makes
 * {@code entity.tick()} run exactly as before; skipping the Post construction is
 * observationally identical to posting to an empty bus. When a listener exists the
 * original call runs untouched.</p>
 *
 * <p>The query ({@link EventBusQuery}) is read-only and lock-free, safe on region /
 * dimension workers where QuarryPlus-style machines tick.</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_EntityTickShortcircuit {

    @Unique
    private static final EntityTickEvent.Pre CACHED_PRE = new EntityTickEvent.Pre(null);

    @Redirect(method = "tickNonPassenger",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/neoforged/neoforge/event/EventHooks;fireEntityTickPre(Lnet/minecraft/world/entity/Entity;)Lnet/neoforged/neoforge/event/tick/EntityTickEvent$Pre;"))
    private EntityTickEvent.Pre arclight$fireEntityTickPre(Entity entity) {
        if (PRTSFeaturesConfig.eventShortcircuitEntityTickEnabled
                && !EventBusQuery.hasListeners(EntityTickEvent.Pre.class)) {
            EventShortcircuitStats.increment("skippedPre");
            return CACHED_PRE;
        }
        EventShortcircuitStats.increment("forwardedPre");
        return EventHooks.fireEntityTickPre(entity);
    }

    @Redirect(method = "tickNonPassenger",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/neoforged/neoforge/event/EventHooks;fireEntityTickPost(Lnet/minecraft/world/entity/Entity;)V"))
    private void arclight$fireEntityTickPost(Entity entity) {
        if (PRTSFeaturesConfig.eventShortcircuitEntityTickEnabled
                && !EventBusQuery.hasListeners(EntityTickEvent.Post.class)) {
            EventShortcircuitStats.increment("skippedPost");
            return;
        }
        EventShortcircuitStats.increment("forwardedPost");
        EventHooks.fireEntityTickPost(entity);
    }
}
