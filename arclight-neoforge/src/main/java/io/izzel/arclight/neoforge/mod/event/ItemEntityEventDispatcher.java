package io.izzel.arclight.neoforge.mod.event;

import io.izzel.arclight.common.optimization.general.eventbridge.EventBridgeStats;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.bukkit.event.entity.ItemDespawnEvent;

public class ItemEntityEventDispatcher {

    @SubscribeEvent(receiveCanceled = true)
    public void onExpire(ItemExpireEvent event) {
        // P0-2 precheck: no plugin listens to ItemDespawnEvent -> skip construction +
        // empty dispatch. Without listeners the dispatch never cancels, so the
        // setExtraLife(1) branch is unreachable anyway.
        if (ItemDespawnEvent.getHandlerList().getRegisteredListeners().length == 0) {
            EventBridgeStats.increment("skippedEvents");
            return;
        }
        EventBridgeStats.increment("forwardedEvents");
        if (CraftEventFactory.callItemDespawnEvent(event.getEntity()).isCancelled()) {
            event.setExtraLife(1);
        }
    }
}
