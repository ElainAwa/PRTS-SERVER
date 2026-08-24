/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore.ownership;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.time.Instant;
import java.util.List;

/**
 * 路由学习持久化 JSON 读写。
 * 将 ClassAffinityLedger 的 auto-learned routes 序列化到独立 JSON 文件，
 * 启动时加载恢复，避免与用户手工 force 列表混淆。
 */
public final class LearnedRoutePersistence {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LearnedRoutePersistence() {
    }

    /** 启动时加载 learned-routes.json 并恢复到 ClassAffinityLedger。 */
    public static void loadOnStartup() {
        if (!PRTSFeaturesConfig.persistLearnedRoutes) {
            return;
        }
        File file = new File(PRTSFeaturesConfig.learnedRoutesFile);
        if (!file.exists()) {
            LOGGER.info("[learned-routes] No persisted routes found at {}", file.getAbsolutePath());
            return;
        }
        try (FileReader reader = new FileReader(file)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("routes")) {
                LOGGER.warn("[learned-routes] Invalid JSON format in {}", file.getAbsolutePath());
                return;
            }
            JsonArray routes = root.getAsJsonArray("routes");
            int restored = 0;
            for (int i = 0; i < routes.size(); i++) {
                JsonObject route = routes.get(i).getAsJsonObject();
                String className = route.get("class").getAsString();
                long learnedTick = route.get("learnedTick").getAsLong();
                ClassAffinityLedger.restoreLearnedRoute(className, learnedTick);
                restored++;
            }
            LOGGER.info("[learned-routes] Restored {} learned routes from {}", restored, file.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("[learned-routes] Failed to load from {}: {}", file.getAbsolutePath(), e.toString());
        }
    }

    /** 关服时保存 learned routes 到 JSON 文件。 */
    public static void saveOnShutdown() {
        if (!PRTSFeaturesConfig.persistLearnedRoutes) {
            return;
        }
        List<ClassAffinityLedger.LearnedRoute> routes = ClassAffinityLedger.snapshotLearnedRoutes();
        if (routes.isEmpty()) {
            LOGGER.info("[learned-routes] No auto-learned routes to persist");
            return;
        }
        // 限制数量
        int limit = PRTSFeaturesConfig.learnedRoutesLimit;
        if (routes.size() > limit) {
            LOGGER.warn("[learned-routes] Snapshot has {} routes, limiting to {}", routes.size(), limit);
            routes = routes.subList(0, limit);
        }
        File file = new File(PRTSFeaturesConfig.learnedRoutesFile);
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("learnedAt", Instant.now().toString());
            JsonArray array = new JsonArray();
            for (ClassAffinityLedger.LearnedRoute route : routes) {
                JsonObject obj = new JsonObject();
                obj.addProperty("class", route.className());
                obj.addProperty("violations", route.violations());
                obj.addProperty("learnedTick", route.learnedTick());
                array.add(obj);
            }
            root.add("routes", array);
            GSON.toJson(root, writer);
            LOGGER.info("[learned-routes] Saved {} learned routes to {}", routes.size(), file.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("[learned-routes] Failed to save to {}: {}", file.getAbsolutePath(), e.toString());
        }
    }
}
