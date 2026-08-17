package io.izzel.arclight.neoforge.mod.event;

import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.common.optimization.general.eventbridge.EventBridgeRegistry;
import io.izzel.arclight.common.optimization.general.eventbridge.EventBridgeStats;
import net.neoforged.neoforge.common.NeoForge;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Forge event bridge lifecycle (plan 2026-08-17 P0-1).
 *
 * <p>Instead of registering all dispatchers unconditionally at mod construction,
 * each bridge dispatcher is now gate-driven: {@link EventBridgeRegistry} tracks
 * whether any plugin listens to the Bukkit events the dispatcher produces, and
 * this class registers/unregisters the dispatcher on {@link NeoForge#EVENT_BUS}
 * on 0→1 / 1→0 gate flips. On servers with no plugins listening to those events
 * the dispatchers are simply absent from the bus — the bridge overhead (craft
 * block / event construction + empty Bukkit dispatch) is zero.</p>
 *
 * <p>{@code PRTSCommandDispatcher} is not a bridge and stays resident.</p>
 *
 * <p>Known deviation: bridge listener ordering moves from "mod-load" to
 * "first plugin enable"; {@code event-bridge.on-demand-registration.eager-registration}
 * restores the old always-registered behavior for ordering-sensitive setups.</p>
 */
public final class ArclightEventDispatcherRegistry implements EventBridgeRegistry.PlatformBridge {

    /** Binding order must match the registerGate() calls in {@link #init()}. */
    private static final Object[] DISPATCHERS = {
            new BlockBreakEventDispatcher(),   // 0: BlockBreakEvent ∪ BlockDropItemEvent (capture chain)
            new BlockPlaceEventDispatcher(),   // 1: BlockPlaceEvent (BlockMultiPlaceEvent shares its HandlerList)
            new EntityEventDispatcher(),       // 2: EntityTameEvent
            new EntityTeleportEventDispatcher(), // 3: PlayerTeleportEvent ∪ EntityTeleportEvent
            new ItemEntityEventDispatcher(),   // 4: ItemDespawnEvent
    };
    private static final boolean[] REGISTERED = new boolean[DISPATCHERS.length];

    private static final ArclightEventDispatcherRegistry INSTANCE = new ArclightEventDispatcherRegistry();

    private ArclightEventDispatcherRegistry() {
    }

    /** Called from {@code ArclightMod} construction (mod load, before plugin enable). */
    public static void init() {
        // The /prts command dispatcher is not a bridge; without it the command would
        // never reach the brigadier dispatcher -> "Unknown command".
        NeoForge.EVENT_BUS.register(new PRTSCommandDispatcher());
        EventBridgeRegistry.registerGate(BlockBreakEvent.class, BlockDropItemEvent.class);
        EventBridgeRegistry.registerGate(BlockPlaceEvent.class);
        EventBridgeRegistry.registerGate(EntityTameEvent.class);
        EventBridgeRegistry.registerGate(PlayerTeleportEvent.class, EntityTeleportEvent.class);
        EventBridgeRegistry.registerGate(ItemDespawnEvent.class);
        EventBridgeRegistry.setBridge(INSTANCE);
        ArclightServer.LOGGER.info("registry.forge-event (on-demand gate-driven)");
    }

    @Override
    public void onGateChanged(int bindingIndex, boolean shouldRegister) {
        if (shouldRegister) {
            register(bindingIndex);
        } else {
            unregister(bindingIndex);
        }
    }

    private static void register(int index) {
        if (!REGISTERED[index]) {
            NeoForge.EVENT_BUS.register(DISPATCHERS[index]);
            REGISTERED[index] = true;
            EventBridgeStats.increment("dispatcherRegister");
        }
    }

    private static void unregister(int index) {
        if (REGISTERED[index]) {
            NeoForge.EVENT_BUS.unregister(DISPATCHERS[index]);
            REGISTERED[index] = false;
            EventBridgeStats.increment("dispatcherUnregister");
        }
    }
}
