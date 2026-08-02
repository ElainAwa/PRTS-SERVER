package io.izzel.arclight.common.optimization.general.servercore.dynamic;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 动态性能调节管理器（移植自 ServerCore DynamicManager）。
 * 去掉 SparkDynamicManager：MSPT 直接取自 MinecraftServer.getAverageTickTime()。
 * 去掉客户端分支（net.minecraft.client.Minecraft），仅保留服务端行为。
 * 1.20.1 无 mob_spawning 包，故移除 modifyMobcaps（其依赖 mob_spawning.IMobCategory）。
 */
public class DynamicManager {
    private static final List<LinkedSetting> SETTINGS = new ArrayList<>();
    private final MinecraftServer server;
    private double averageTickTime;
    private int count;

    public DynamicManager(MinecraftServer server) {
        this.server = server;
        DynamicSetting.initDefaultValues(this);
    }

    public static DynamicManager getInstance(MinecraftServer server) {
        return ((IMinecraftServer) server).servercore$getDynamicManager();
    }

    public static void reload() {
        SETTINGS.clear();

        List<Setting> settings = ServerCoreConfig.dynamic().settings();
        DynamicSetting.recalculateValues(settings);

        for (Setting setting : settings) {
            SETTINGS.add(new LinkedSetting(setting));
        }

        for (int i = 0; i < SETTINGS.size(); i++) {
            LinkedSetting linked = SETTINGS.get(i);
            linked.initialize(
                    i == 0 ? null : SETTINGS.get(i - 1), // prev
                    i == SETTINGS.size() - 1 ? null : SETTINGS.get(i + 1) // next
            );
        }
    }

    public static void update(MinecraftServer server) {
        if (server.getTickCount() % 20 == 0) {
            DynamicManager manager = getInstance(server);
            if (manager == null) {
                manager = new DynamicManager(server);
                ((IMinecraftServer) server).servercore$setDynamicManager(manager);
            }
            manager.updateValues();

            DynamicConfig config = ServerCoreConfig.dynamic();
            if (config.enabled()) {
                manager.runPerformanceChecks(config);
            }
        }
    }

    private void updateValues() {
        this.averageTickTime = this.calculateAverageTickTime();
        this.count++;
    }

    protected double calculateAverageTickTime() {
        return this.server.getAverageTickTime();
    }

    private void runPerformanceChecks(DynamicConfig config) {
        final double targetMspt = config.targetMspt();
        final boolean decrease = this.averageTickTime > targetMspt + 5;
        final boolean increase = this.averageTickTime < Math.max(targetMspt - 5, 2);

        if (decrease || increase) {
            List<LinkedSetting> ordered = SETTINGS;
            if (increase) {
                ordered = new ArrayList<>(SETTINGS);
                Collections.reverse(ordered);
            }
            for (LinkedSetting setting : ordered) {
                if (setting.shouldRun(this.count) && setting.modify(increase, this)) {
                    break;
                }
            }
        }
    }

    public void modifyViewDistance(int distance) {
        this.server.getPlayerList().setViewDistance(distance);
    }

    public void modifySimulationDistance(int distance) {
        this.server.getPlayerList().setSimulationDistance(distance);
    }

    public double getAverageTickTime() {
        return this.averageTickTime;
    }
}
