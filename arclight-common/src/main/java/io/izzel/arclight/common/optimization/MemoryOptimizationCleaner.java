package io.izzel.arclight.common.optimization;

import io.izzel.arclight.i18n.ArclightConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Collection;

/**
 * 实验性内存缓存清理（默认关闭）。
 * 仅在主线程 tick 中调用，反射清理用户配置的安全缓存目标。
 * 任何单个目标失败都不会影响其它目标或服务端运行。
 *
 * 配置项（prts.conf / optimization 段）：
 *   memory-cache-cleanup-enabled = false
 *   memory-cache-cleanup-interval = 300      // 秒
 *   memory-cache-cleanup-targets = []        // 形如 "com.foo.Bar" 或 "com.foo.Bar#field"
 */
public class MemoryOptimizationCleaner {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-MemoryCleanup");
    private static long lastRunMs = 0;
    private static boolean started = false;

    /** 由 MinecraftServerMixin_MemoryCleanup 在每个 tick 调用 */
    public static void tick() {
        var opt = ArclightConfig.spec().getOptimization();
        if (!opt.isMemoryCacheCleanupEnabled()) return;
        List<String> targets = opt.getMemoryCacheCleanupTargets();
        if (targets == null || targets.isEmpty()) {
            if (!started) {
                started = true;
                LOGGER.info("memory-optimization 已启用，但未配置 memory-cache-cleanup-targets，无缓存可清理。");
            }
            return;
        }
        long now = System.currentTimeMillis();
        long intervalMs = (long) opt.getMemoryCacheCleanupInterval() * 1000L;
        if (lastRunMs != 0 && now - lastRunMs < intervalMs) return;
        lastRunMs = now;
        for (String target : targets) {
            try {
                clearTarget(target);
            } catch (Throwable t) {
                LOGGER.warn("清理缓存目标 {} 失败: {}", target, t.getMessage());
            }
        }
    }

    private static void clearTarget(String target) throws Exception {
        int hash = target.indexOf('#');
        Class<?> clazz;
        Field field = null;
        if (hash >= 0) {
            clazz = Class.forName(target.substring(0, hash));
            field = clazz.getDeclaredField(target.substring(hash + 1));
            field.setAccessible(true);
        } else {
            clazz = Class.forName(target);
        }
        // 优先尝试静态 clear() 方法
        try {
            Method m = clazz.getDeclaredMethod("clear");
            m.setAccessible(true);
            m.invoke(null);
            LOGGER.debug("已调用 {}.clear()", clazz.getName());
            return;
        } catch (NoSuchMethodException ignored) {
            // 没有 clear() 方法，继续尝试字段
        }
        if (field != null) {
            Object val = field.get(null);
            if (val instanceof Map) {
                ((Map<?, ?>) val).clear();
            } else if (val instanceof Collection) {
                ((Collection<?>) val).clear();
            } else {
                LOGGER.warn("目标 {} 既无 clear() 也非 Map/Collection，跳过", target);
                return;
            }
            LOGGER.debug("已清空字段 {}", target);
            return;
        }
        LOGGER.warn("未找到可清理目标: {}", target);
    }
}
