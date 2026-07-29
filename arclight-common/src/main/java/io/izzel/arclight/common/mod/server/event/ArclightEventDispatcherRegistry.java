package io.izzel.arclight.common.mod.server.event;

import io.izzel.arclight.common.mod.ArclightMod;
import io.izzel.arclight.common.mod.command.PRTSCommand;
import net.minecraftforge.common.MinecraftForge;

public abstract class ArclightEventDispatcherRegistry {

    public static void registerAllEventDispatchers() {
        MinecraftForge.EVENT_BUS.register(new BlockBreakEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new BlockPlaceEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new EntityPotionEffectEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new EntityEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new EntityTeleportEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new ItemEntityEventDispatcher());
        MinecraftForge.EVENT_BUS.register(new WorldEventDispatcher());
        // Register the /prts command dispatcher (static @SubscribeEvent onRegisterCommands).
        MinecraftForge.EVENT_BUS.register(PRTSCommand.class);
        ArclightMod.LOGGER.info("registry.forge-event");
    }

}
