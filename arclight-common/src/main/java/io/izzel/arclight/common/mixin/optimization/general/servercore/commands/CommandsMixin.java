package io.izzel.arclight.common.mixin.optimization.general.servercore.commands;

import com.mojang.brigadier.CommandDispatcher;
import io.izzel.arclight.common.optimization.general.servercore.commands.MobcapsCommand;
import io.izzel.arclight.common.optimization.general.servercore.commands.ServerCoreCommands;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Commands 构造返回时注册 ServerCore 命令（移植自 ServerCore Events.registerCommands）。
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Shadow
    @Final
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void prts$registerCommands(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
        ServerCoreCommands.register(this.dispatcher);
        MobcapsCommand.register(this.dispatcher);
    }
}
