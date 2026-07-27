package io.izzel.arclight.neoforge.mod.event;

import io.izzel.arclight.common.mod.command.PRTSCommand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

// Bridges NeoForge's RegisterCommandsEvent to the loader-agnostic /prts command
public class PRTSCommandDispatcher {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        PRTSCommand.register(event.getDispatcher());
    }
}
