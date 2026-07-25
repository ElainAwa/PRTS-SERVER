package io.izzel.arclight.common.compat.luminara;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Luminara 轻量防卡功能的配置。
 * 默认全部关闭（遵循「零感知底层优化、不改玩法」原则），仅在 luminara-features.yml 显式开启后生效。
 * 去 Youer 化：替代 YouerConfig，不依赖 Youer 任何类。
 */
public class LuminaraFeaturesConfig {

    public static YamlConfiguration config;

    // EntityClear - 定时清理掉落物
    public static boolean clearItemEnabled;
    public static long clearItemInterval;
    public static List<String> clearItemWhitelist;
    public static String clearItemMsg;

    // EntityClear - 定时清理怪物（无自定义名）
    public static boolean clearMonsterEnabled;
    public static long clearMonsterInterval;
    public static List<String> clearMonsterWhitelist;
    public static String clearMonsterMsg;

    // Watchdog - 主线程卡顿看门狗（默认关；开启后主线程 > threshold-ms 未推进即告警并 dump 栈）
    public static boolean watchdogEnabled;
    public static long watchdogThresholdMs;
    public static long watchdogWarnCooldownMs;

    public static void init() {
        File file = new File("luminara-features.yml");
        config = YamlConfiguration.loadConfiguration(file);
        clearItemEnabled = config.getBoolean("entity-clear.item.enabled", false);
        clearItemInterval = config.getLong("entity-clear.item.interval-seconds", 300);
        clearItemWhitelist = new ArrayList<>(config.getStringList("entity-clear.item.whitelist"));
        clearItemMsg = config.getString("entity-clear.item.message", "");
        clearMonsterEnabled = config.getBoolean("entity-clear.monster.enabled", false);
        clearMonsterInterval = config.getLong("entity-clear.monster.interval-seconds", 600);
        clearMonsterWhitelist = new ArrayList<>(config.getStringList("entity-clear.monster.whitelist"));
        clearMonsterMsg = config.getString("entity-clear.monster.message", "");
        watchdogEnabled = config.getBoolean("watchdog.enabled", false);
        watchdogThresholdMs = config.getLong("watchdog.threshold-ms", 2000);
        watchdogWarnCooldownMs = config.getLong("watchdog.warn-cooldown-ms", 60000);
    }
}
