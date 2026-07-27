package io.izzel.arclight.common.compat.prts;

import io.izzel.arclight.common.compat.prts.feature.EntityClear;
import io.izzel.arclight.common.compat.prts.feature.PRTSThreadCost;
import io.izzel.arclight.common.compat.prts.feature.WatchMohist;
import java.util.ArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.CraftServer;

/**
 * PRTS 轻量防卡功能的统一启动/停止聚合。
 * 由 MinecraftServerMixin.arclight$enablePlugins（POSTWORLD 插件启用点）调用 start()，
 * 由 arclight$setStopped（stopServer）调用 stop()，由 arclight$updateTickParam（每 tick）调用 tick()。
 * 去 Youer 化：替代 YouerPlugin 的 feature 启动逻辑，不依赖 Youer 任何类。
 */
public class PRTSFeatures {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Features");

    public static void start() {
        PRTSFeaturesConfig.init();
        EntityClear.start();
        WatchMohist.start();
        registerCommands();
        LOGGER.info("[PRTS-Features] started | EntityClear(item={},monster={}) Watchdog={}",
                PRTSFeaturesConfig.clearItemEnabled, PRTSFeaturesConfig.clearMonsterEnabled,
                PRTSFeaturesConfig.watchdogEnabled);
    }

    public static void tick() {
        WatchMohist.update();
    }

    public static void stop() {
        EntityClear.stop();
        WatchMohist.stop();
    }

    private static void registerCommands() {
        Command cmd = new Command("prtsfeatures", "PRTS 诊断命令", "/prtsfeatures threadcost", new ArrayList<>()) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                if (args.length >= 1 && args[0].equalsIgnoreCase("threadcost")) {
                    PRTSThreadCost.dumpThreadCpuTime(sender);
                    return true;
                }
                sender.sendMessage("§e[PRTS] 用法: /prtsfeatures threadcost");
                return true;
            }
        };
        ((CraftServer) Bukkit.getServer()).getCommandMap().register("prts", cmd);
        LOGGER.info("[PRTS-Features] registered command /prtsfeatures threadcost");
    }
}
