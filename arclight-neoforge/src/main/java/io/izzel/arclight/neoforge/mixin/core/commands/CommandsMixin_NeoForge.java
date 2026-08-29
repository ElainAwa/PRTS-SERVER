package io.izzel.arclight.neoforge.mixin.core.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import io.izzel.arclight.common.bridge.core.commands.CommandsBridge;
import io.izzel.arclight.common.mod.util.BukkitDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.server.command.CommandHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Function;

@Mixin(Commands.class)
public abstract class CommandsMixin_NeoForge implements CommandsBridge {

    // @formatter:off
    @Mutable @Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;
    // @formatter:on

    @Inject(method = "<init>", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/commands/Commands;dispatcher:Lcom/mojang/brigadier/CommandDispatcher;",
            shift = At.Shift.AFTER))
    private void arclight$neo$installBukkitDispatcher(CallbackInfo ci) {
        // 根因：common 的 @CreateConstructor 构造器注入点在 super() 之后、字段初始化器之前；
        // NeoForge merged 的 Commands.<init> 把 dispatcher 字段初始化（new CommandDispatcher
        // + putfield）编译在构造器主体开头，会把 mixin 的替换覆盖掉，导致 register() 覆写从未
        // 生效、vanilla 与 mod 命令都不进 Bukkit commandMap。此处在 putfield 之后立即把
        // dispatcher 换回 BukkitDispatcher，使后续注册（vanilla + RegisterCommandsEvent 的 mod
        // 命令）都走覆写的 register()。
        this.dispatcher = new BukkitDispatcher((Commands) (Object) this);
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", remap = false,
            target = "Lnet/neoforged/neoforge/event/EventHooks;onCommandRegister(Lcom/mojang/brigadier/CommandDispatcher;Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V"))
    private void arclight$neo$enterModPhase(CallbackInfo ci) {
        // vanilla 注册（字节码 offset 15–686，构造器内 EventHooks.onCommandRegister 之前）
        // 期间 modPhase=false：只进 dispatcher root，不包装进 Bukkit commandMap（vanilla 由
        // CraftServer.setVanillaCommands 以 minecraft:* 注册）。onCommandRegister 触发
        // RegisterCommandsEvent（bus.post 同步执行监听器）前置 true，此后 mod / NeoForge
        // 自身命令才以 neoforge:* 注册进 Bukkit commandMap。
        ((BukkitDispatcher) this.dispatcher).setModPhase(true);
    }

    @Override
    public <S, T> void bridge$forge$mergeNode(CommandNode<S> sourceNode, CommandNode<T> resultNode,
                                              Map<CommandNode<S>, CommandNode<T>> sourceToResult,
                                              S canUse, Command<T> execute,
                                              Function<SuggestionProvider<S>, SuggestionProvider<T>> sourceToResultSuggestion) {
        CommandHelper.mergeCommandNode(sourceNode, resultNode, sourceToResult, canUse, execute, sourceToResultSuggestion);
    }
}
