package io.izzel.arclight.common.optimization.general.servercore.dynamic;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.IMobCategory;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.MobCategory;

import java.util.List;

/**
 * 动态性能调节管理器（移植自 ServerCore DynamicManager）。
 * 去掉 SparkDynamicManager：MSPT 直接取自 MinecraftServer.getCurrentSmoothedTickTime()。
 * 去掉客户端分支（net.minecraft.client.Minecraft），仅保留服务端行为。
 */
public class DynamicManager {
    private static final List<LinkedSetting> SETTINGS = new ObjectArrayList<>();
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
        return this.server.getCurrentSmoothedTickTime();
    }

    private void runPerformanceChecks(DynamicConfig config) {
        final double targetMspt = config.targetMspt();
        final boolean decrease = this.averageTickTime > targetMspt + 5;
        final boolean increase = this.averageTickTime < Math.max(targetMspt - 5, 2);

        if (decrease || increase) {
            Iterable<LinkedSetting> ordered = increase ? SETTINGS.reversed() : SETTINGS;
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

    public static void modifyMobcaps(int percentage) {
        final double modifier = percentage / 100F;
        for (MobCategory category : MobCategory.values()) {
            IMobCategory.modifyCapacity(category, modifier);
        }
    }

    public double getAverageTickTime() {
        return this.averageTickTime;
    }
}
