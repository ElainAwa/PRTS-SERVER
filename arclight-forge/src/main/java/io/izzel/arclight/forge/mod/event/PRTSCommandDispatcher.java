package io.izzel.arclight.forge.mod.event;

import io.izzel.arclight.common.mod.command.PRTSCommand;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// Bridges Forge's RegisterCommandsEvent to the loader-agnostic /prts command
public class PRTSCommandDispatcher {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        PRTSCommand.register(event.getDispatcher());
    }
}
