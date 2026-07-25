package io.izzel.arclight.common.compat.luminara.feature;

import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.compat.luminara.LuminaraFeaturesConfig;
import io.izzel.arclight.common.mod.server.ArclightServer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.NamedThreadFactory;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;

/**
 * 定时清理世界中的掉落物 / 无名怪物，缓解实体爆量导致的卡顿。
 * 移植自 Youer feature.EntityClear，已去 Youer 化：
 *   - YouerConfig.* 配置 → LuminaraFeaturesConfig（luminara-features.yml，默认全关）
 *   - 服务器停止判断 → MinecraftServerBridge.hasStopped()
 * 默认不开启，不改变玩法；仅当用户在配置中显式启用后才生效。
 */
public class EntityClear {

    public static final ScheduledExecutorService ENTITYCLEAR_ITEM = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("EntityClear-Item"));
    public static final ScheduledExecutorService ENTITYCLEAR_MONSTER = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("EntityClear-Monster"));

    public static void start() {
        if (LuminaraFeaturesConfig.clearItemEnabled) {
            ENTITYCLEAR_ITEM.scheduleAtFixedRate(() -> {
                if (isStopped()) return;
                run_item();
            }, 60_000L, Math.max(30_000L, LuminaraFeaturesConfig.clearItemInterval * 1000L), TimeUnit.MILLISECONDS);
        }
        if (LuminaraFeaturesConfig.clearMonsterEnabled) {
            ENTITYCLEAR_MONSTER.scheduleAtFixedRate(() -> {
                if (isStopped()) return;
                run_monster();
            }, 60_000L, Math.max(30_000L, LuminaraFeaturesConfig.clearMonsterInterval * 1000L), TimeUnit.MILLISECONDS);
        }
    }

    public static void stop() {
        ENTITYCLEAR_ITEM.shutdown();
        ENTITYCLEAR_MONSTER.shutdown();
    }

    private static boolean isStopped() {
        MinecraftServer server = ArclightServer.getMinecraftServer();
        return server == null || ((MinecraftServerBridge) server).bridge$hasStopped();
    }

    public static void run_item() {
        ArclightServer.executeOnMainThread(() -> {
            AtomicInteger size = new AtomicInteger(0);
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof Item item) {
                        if (!LuminaraFeaturesConfig.clearItemWhitelist.contains(item.getItemStack().getType().name())) {
                            entity.remove();
                            size.incrementAndGet();
                        }
                    }
                }
            }
            if (!LuminaraFeaturesConfig.clearItemMsg.isEmpty()) {
                Bukkit.broadcastMessage(LuminaraFeaturesConfig.clearItemMsg.replace("%size%", String.valueOf(size.getAndSet(0))));
            }
        });
    }

    public static void run_monster() {
        ArclightServer.executeOnMainThread(() -> {
            AtomicInteger size = new AtomicInteger(0);
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof Monster monster) {
                        if (!LuminaraFeaturesConfig.clearMonsterWhitelist.contains(monster.getType().name()) && monster.getCustomName() == null) {
                            entity.remove();
                            size.incrementAndGet();
                        }
                    }
                }
            }
            if (!LuminaraFeaturesConfig.clearMonsterMsg.isEmpty()) {
                Bukkit.broadcastMessage(LuminaraFeaturesConfig.clearMonsterMsg.replace("%size%", String.valueOf(size.getAndSet(0))));
            }
        });
    }
}
