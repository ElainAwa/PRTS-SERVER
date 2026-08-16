package io.izzel.arclight.neoforge.mod.event;

import io.izzel.arclight.common.bridge.core.server.level.ServerPlayerBridge;
import io.izzel.arclight.common.mod.util.ArclightCaptures;
import io.izzel.arclight.common.mod.util.DistValidate;
import io.izzel.arclight.common.optimization.general.eventbridge.EventBridgeStats;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.block.CraftBlock;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;

public class BlockBreakEventDispatcher {

    /**
     * P0-2 precheck: with no plugin listening to either event, the whole bridge
     * (craft block + event construction, capture push, empty dispatch, cancel
     * write-back) is a no-op — skip it entirely.
     *
     * <p>Capture-chain exception: {@code BlockDropItemEvent} listeners consume the
     * captured drop list pushed by {@code captureBlockBreakPlayer} (BlockMixin /
     * ServerPlayerGameModeMixin call {@code handleBlockDropItemEvent} with it). If
     * only {@code BlockDropItemEvent} is listened, the event must still be built and
     * pushed (the context needs a live event), but the empty {@code callEvent} and
     * cancel write-back are skipped.</p>
     */
    // todo
    @SubscribeEvent(receiveCanceled = true)
    public void onBreakBlock(BlockEvent.BreakEvent event) {
        if (DistValidate.isValid(event.getLevel())) {
            boolean breakListened = BlockBreakEvent.getHandlerList().getRegisteredListeners().length > 0;
            boolean dropListened = BlockDropItemEvent.getHandlerList().getRegisteredListeners().length > 0;
            if (!breakListened && !dropListened) {
                EventBridgeStats.increment("skippedEvents");
                return;
            }
            CraftBlock craftBlock = CraftBlock.at(event.getLevel(), event.getPos());
            BlockBreakEvent breakEvent = new BlockBreakEvent(craftBlock, ((ServerPlayerBridge) event.getPlayer()).bridge$getBukkitEntity());
            ArclightCaptures.captureBlockBreakPlayer(breakEvent);
            if (!breakListened) {
                // capture-chain only (BlockDropItemEvent listeners present): the push
                // above keeps the drop chain alive; the empty dispatch and the cancel
                // write-back are no-ops without BlockBreakEvent listeners.
                EventBridgeStats.increment("capturedOnly");
                return;
            }
            EventBridgeStats.increment("forwardedEvents");
            breakEvent.setCancelled(event.isCanceled());
            //breakEvent.setExpToDrop(event.getExpToDrop());
            Bukkit.getPluginManager().callEvent(breakEvent);
            event.setCanceled(breakEvent.isCancelled());
            //event.setExpToDrop(breakEvent.getExpToDrop());
        }
    }
}
