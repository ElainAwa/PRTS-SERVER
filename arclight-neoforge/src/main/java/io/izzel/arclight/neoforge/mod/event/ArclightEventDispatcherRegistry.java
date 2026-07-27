package io.izzel.arclight.neoforge.mod.event;

import io.izzel.arclight.common.mod.server.ArclightServer;
import net.neoforged.neoforge.common.NeoForge;

public abstract class ArclightEventDispatcherRegistry {

    public static void registerAllEventDispatchers() {
        NeoForge.EVENT_BUS.register(new BlockBreakEventDispatcher());
        NeoForge.EVENT_BUS.register(new BlockPlaceEventDispatcher());
        NeoForge.EVENT_BUS.register(new EntityEventDispatcher());
        NeoForge.EVENT_BUS.register(new EntityTeleportEventDispatcher());
        NeoForge.EVENT_BUS.register(new ItemEntityEventDispatcher());
        // Register the /prts command dispatcher; without this the command
        // would never reach the brigadier dispatcher -> "Unknown command".
        NeoForge.EVENT_BUS.register(new PRTSCommandDispatcher());
        ArclightServer.LOGGER.info("registry.forge-event");
    }
}
