package io.izzel.arclight.forge.mod.event;

import io.izzel.arclight.common.mod.server.ArclightServer;
import net.minecraftforge.common.MinecraftForge;

public abstract class ArclightEventDispatcherRegistry {

    public static void registerAllEventDispatchers() {
        MinecraftForge.EVENT_BUS.register(new BlockBreakEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new BlockPlaceEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new EntityEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new EntityTeleportEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new ItemEntityEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new ChannelRegisterHandler());
        // Register the /prts command dispatcher; without this the command
        // would never reach the brigadier dispatcher -> "Unknown command".
        MinecraftForge.EVENT_BUS.register(new PRTSCommandDispatcher());
        ArclightServer.LOGGER.info("registry.forge-event");
    }
}
