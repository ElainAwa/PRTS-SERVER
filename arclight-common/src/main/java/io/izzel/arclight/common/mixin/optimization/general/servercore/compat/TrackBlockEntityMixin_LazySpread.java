/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Create 长轨道尖峰分摊：TrackBlockEntity.lazyTick 会对每条 primary 弯道整体
 * rasterise 并逐格写假轨道，单 tick 可达数百毫秒。开启
 * parallel.create-track-lazy-spread 后每 tick 只处理一条连接、且每连接只推进
 * create-track-lazy-chunk-blocks 个栅格块，剩余部分在后续 lazyTick 自然续做。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = TrackBlockEntity.class, remap = false)
public abstract class TrackBlockEntityMixin_LazySpread {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-CreateCompat");

    @Unique
    private static final ThreadLocal<Boolean> ARCLIGHT_CHUNKED = ThreadLocal.withInitial(() -> false);

    @Unique
    private static final ConcurrentHashMap<BezierConnection, int[]> CURSORS = new ConcurrentHashMap<>();

    @Shadow
    public Map<BlockPos, BezierConnection> connections;

    @Shadow
    public void manageFakeTracksAlong(BezierConnection connection, boolean remove) {
    }

    @Unique
    private int arclight$primaryCursor;

    @Inject(method = "lazyTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void arclight$spreadLazyTick(CallbackInfo ci) {
        if (!PRTSFeaturesConfig.createTrackLazySpread) {
            return;
        }
        List<BezierConnection> primaries = new ArrayList<>();
        for (BezierConnection connection : this.connections.values()) {
            if (connection != null && connection.isPrimary()) {
                primaries.add(connection);
            }
        }
        if (primaries.isEmpty()) {
            return;
        }
        BezierConnection chosen = primaries.get(Math.floorMod(this.arclight$primaryCursor++, primaries.size()));
        ARCLIGHT_CHUNKED.set(true);
        try {
            this.manageFakeTracksAlong(chosen, false);
        } catch (Throwable t) {
            LOGGER.warn("[create-track-spread] manageFakeTracksAlong failed for {}: {}",
                    chosen.getKey(), t.toString());
        } finally {
            ARCLIGHT_CHUNKED.set(false);
        }
        ci.cancel();
    }

    @Redirect(method = "manageFakeTracksAlong",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/track/BezierConnection;rasterise()Ljava/util/Map;"),
            remap = false)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map arclight$chunkRasterise(BezierConnection connection) {
        Map full = connection.rasterise();
        if (!ARCLIGHT_CHUNKED.get()) {
            return full;
        }
        List<Map.Entry> entries = new ArrayList<>(full.entrySet());
        // 栅格键是 Pair<Integer,Integer>；用字符串排序得到跨调用稳定的顺序，
        // 同时避免对 Catnip Pair 的编译期依赖。
        entries.sort(Comparator.comparing(e -> String.valueOf(e.getKey())));
        if (entries.isEmpty()) {
            return full;
        }
        int[] state = CURSORS.computeIfAbsent(connection, ignored -> new int[]{0});
        int offset = state[0];
        if (offset >= entries.size()) {
            offset = 0;
        }
        int budget = PRTSFeaturesConfig.createTrackLazyChunkBlocks;
        LinkedHashMap chunk = new LinkedHashMap();
        int taken = 0;
        for (int i = offset; i < entries.size() && taken < budget; i++, taken++) {
            Map.Entry entry = entries.get(i);
            chunk.put(entry.getKey(), entry.getValue());
        }
        int next = offset + taken;
        if (next >= entries.size()) {
            CURSORS.remove(connection);
        } else {
            state[0] = next;
        }
        return chunk;
    }
}
