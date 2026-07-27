package io.izzel.arclight.fabric.mod.event;

import io.izzel.arclight.common.mod.command.PRTSCommand;
import io.izzel.arclight.common.mod.server.event.EntityEventHandler;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class EventHandlerRegistry {
    public static void register() {
        LivingDropsEvent.EVENT.register(EntityEventHandler::monitorLivingDrops);
        S2CPlayNConfigChannelHandler.register();
        // Register the /prts command via Fabric's command registration callback
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> PRTSCommand.register(dispatcher));
    }
}
