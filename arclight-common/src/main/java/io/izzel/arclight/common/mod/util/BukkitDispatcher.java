package io.izzel.arclight.common.mod.util;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.izzel.arclight.common.mod.server.ArclightServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.command.BukkitCommandWrapper;
import org.bukkit.craftbukkit.v.command.VanillaCommandWrapper;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BukkitDispatcher extends CommandDispatcher<CommandSourceStack> {

    /**
     * Commands 在 dataPackResources（WorldLoader.load）阶段构造，早于 CraftServer
     * （PlayerList.<init> 里 new CraftServer）；此时 Bukkit.getServer() 为 null，
     * 命令包装暂存此队列，CraftServer 构造完成后由平台层调用 {@link #flushPending()}
     * 统一注册进 Bukkit commandMap。
     */
    private static final Queue<VanillaCommandWrapper> PENDING = new ConcurrentLinkedQueue<>();

    private final Commands commands;

    public BukkitDispatcher(Commands commands) {
        this.commands = commands;
    }

    @Override
    public LiteralCommandNode<CommandSourceStack> register(LiteralArgumentBuilder<CommandSourceStack> command) {
        LiteralCommandNode<CommandSourceStack> node = command.build();
        if (!(node.getCommand() instanceof BukkitCommandWrapper)) {
            VanillaCommandWrapper wrapper = new VanillaCommandWrapper(this.commands, node);
            Server server = Bukkit.getServer();
            if (server == null) {
                PENDING.add(wrapper);
            } else {
                ((CraftServer) server).getCommandMap().register("forge", wrapper);
            }
        }
        getRoot().addChild(node);
        return node;
    }

    /** CraftServer 构造完成后由平台层调用，把启动早期入队的命令补注册进 Bukkit commandMap。幂等。 */
    public static void flushPending() {
        CraftServer server = (CraftServer) Bukkit.getServer();
        if (server == null) {
            return;
        }
        int flushed = 0;
        VanillaCommandWrapper wrapper;
        while ((wrapper = PENDING.poll()) != null) {
            server.getCommandMap().register("forge", wrapper);
            flushed++;
        }
        if (flushed > 0) {
            ArclightServer.LOGGER.info("forwarded {} deferred command(s) to the bukkit command map", flushed);
        }
    }
}
