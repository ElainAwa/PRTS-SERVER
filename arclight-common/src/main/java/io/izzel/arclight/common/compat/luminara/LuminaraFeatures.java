package io.izzel.arclight.common.compat.luminara;

import io.izzel.arclight.common.compat.luminara.feature.EntityClear;
import io.izzel.arclight.common.compat.luminara.feature.LuminaraThreadCost;
import io.izzel.arclight.common.compat.luminara.feature.WatchMohist;
import java.util.ArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v.CraftServer;

/**
 * Luminara 轻量防卡功能的统一启动/停止聚合。
 * 由 MinecraftServerMixin.arclight$enablePlugins（POSTWORLD 插件启用点）调用 start()，
 * 由 arclight$setStopped（stopServer）调用 stop()，由 arclight$updateTickParam（每 tick）调用 tick()。
 * 去 Youer 化：替代 YouerPlugin 的 feature 启动逻辑，不依赖 Youer 任何类。
 */
public class LuminaraFeatures {

    private static final Logger LOGGER = LogManager.getLogger("Luminara-Features");

    public static void start() {
        LuminaraFeaturesConfig.init();
        EntityClear.start();
        WatchMohist.start();
        registerCommands();
        LOGGER.info("[Luminara-Features] started | EntityClear(item={},monster={}) Watchdog={}",
                LuminaraFeaturesConfig.clearItemEnabled, LuminaraFeaturesConfig.clearMonsterEnabled,
                LuminaraFeaturesConfig.watchdogEnabled);
    }

    public static void tick() {
        WatchMohist.update();
    }

    public static void stop() {
        EntityClear.stop();
        WatchMohist.stop();
    }

    private static void registerCommands() {
        Command cmd = new Command("luminarafeatures", "Luminara 诊断命令", "/luminarafeatures threadcost", new ArrayList<>()) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                if (args.length >= 1 && args[0].equalsIgnoreCase("threadcost")) {
                    LuminaraThreadCost.dumpThreadCpuTime(sender);
                    return true;
                }
                sender.sendMessage("§e[Luminara] 用法: /luminarafeatures threadcost");
                return true;
            }
        };
        ((CraftServer) Bukkit.getServer()).getCommandMap().register("luminara", cmd);
        LOGGER.info("[Luminara-Features] registered command /luminarafeatures threadcost");
    }
}
