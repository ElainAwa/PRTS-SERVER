package io.izzel.arclight.neoforge.mod.event;

import io.izzel.arclight.common.optimization.general.eventbridge.EventBridgeStats;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.bukkit.event.entity.EntityTameEvent;

public class EntityEventDispatcher {

    @SubscribeEvent
    public void onEntityTame(AnimalTameEvent event) {
        // P0-2 precheck: no plugin listens to EntityTameEvent -> skip construction + empty dispatch.
        // Without listeners the dispatch is a no-op and setCanceled(false) would be a write-back
        // of the unchanged value, so skipping is behaviorally identical.
        if (EntityTameEvent.getHandlerList().getRegisteredListeners().length == 0) {
            EventBridgeStats.increment("skippedEvents");
            return;
        }
        EventBridgeStats.increment("forwardedEvents");
        event.setCanceled(CraftEventFactory.callEntityTameEvent(event.getAnimal(), event.getTamer()).isCancelled());
    }
}
