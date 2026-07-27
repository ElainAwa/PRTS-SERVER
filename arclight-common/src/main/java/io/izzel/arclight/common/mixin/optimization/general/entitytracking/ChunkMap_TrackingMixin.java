package io.izzel.arclight.common.mixin.optimization.general.entitytracking;

import io.izzel.arclight.common.optimization.general.entitytracking.NearbyEntityTracking;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [PRTS 本服维护者移植 2026-07-21]
 * 路线B 核心 mixin：HariPlayer 招牌的空间化实体追踪（AreaMap 方案，移植自 VMP 同名算法）的 mojmap 移植。
 *
 * ⚠️ 关键修正（2026-07-21 实测定位）：
 *   Forge 的无参 tick()V 做两件事：
 *     ① 为每个玩家调 updateChunkTracking(player)（Forge 私有包裹法，不在官方映射里），
 *        把玩家新位置的区块标记到其连接 chunkSender 的待发队列（区块包由 PlayerChunkSender 异步发出）
 *        —— 这是 tp 后区块能否加载的唯一驱动；
 *     ② 遍历 entityMap 做实体广播循环（updatePlayers + sendChanges）。
 *   旧版 routeB 直接取消整个 tick()V、只重做 ② 的 AreaMap 版，于是【①被丢掉】→ tp 大跳变时
 *   玩家区块永不进 chunkSender → 客户端虚空 → keepalive 超时。走路正常是因为 move() 的增量
 *   updateChunkTracking 盖住了。
 *
 *   本版改为【绝不取消 tick()V】：
 *     - 用 @Redirect 把 tick()V 里的实体广播循环（entityMap.values() 遍历）替换为空集合，
 *       原版 updateChunkTracking 循环（①）原样运行 → 区块刷新链路 100% 不动；
 *     - 用 @Inject(RETURN) 在 tick()V 末尾跑 AreaMap 的优化实体广播（替代被空掉的 ②）。
 *   这样 Forge 的区块逻辑完全不被触碰，只替换了实体广播机制，tp 后区块照常加载。
 *
 * 门控：optimization.experimental-optimizations-enabled。关闭时本 mixin 完全惰性，行为等同 100% 原版。
 * 三重兜底（任何一条触发都回退原版实体循环，绝不虚空/冻结）：
 *   1) 开关关闭或本会话已标记失败 → 原版运行；
 *   2) areaMap 未维护（isEmpty，如运行时切开关、或 addEntity 钩子未命中）→ 原版运行；
 *   3) nearbyEntityTracking.tick() 抛异常 → 置失败标记，本会话后续全部回退原版。
 */
@Mixin(ChunkMap.class)
public class ChunkMap_TrackingMixin {

    // @formatter:off
    @Shadow @Final private Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;
    // @formatter:on

    @Unique
    private static final Logger LOGGER = LogManager.getLogger("PRTS-EntityTrack");

    // 仅首次成功 tick 时打印一次启用通告（INFO），避免每次重启重复刷屏。
    @Unique
    private static boolean prts$announced = false;
    @Unique
    private static boolean prts$diagLogged = false;

    @Unique
    private final NearbyEntityTracking nearbyEntityTracking = new NearbyEntityTracking();

    @Unique
    private static boolean prts$routeBFailed = false;
    @Unique
    private static int prts$tickCount = 0;
    // 看门狗阈值：沿用 1.20.1 RouteBSpec 默认值（hard=500ms / soft=100ms），可用系统属性覆盖。
    // 运行时由系统属性提供，确保启动后读到的就是设定值。
    @Unique
    private static long prts$watchdogHardMs() {
        return Long.getLong("prts.routeb.watchdog-hard-ms", 500L);
    }
    @Unique
    private static long prts$watchdogSoftMs() {
        return Long.getLong("prts.routeb.watchdog-soft-ms", 100L);
    }

    private static boolean prts$experimentalOn() {
        // 默认启用；可通过 -Dprts.routeb.disabled=true 关闭，回退 100% 原版实体追踪。
        return !Boolean.getBoolean("prts.routeb.disabled");
    }

    /**
     * 重定向 tick()V 里的实体广播循环（entityMap.values() 遍历）。
     * routeB 激活时返回空集合 → 原版实体广播（updatePlayers/sendChanges）被跳过，由下方 AreaMap 接管；
     * 同时 tick()V 顶部的 updateChunkTracking 循环完全不受影响 → 区块刷新照常。
     * routeB 未激活 / 已失败 / AreaMap 未维护时返回真实集合 → 原版实体广播照常运行。
     */
    @Redirect(method = "tick()V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"))
    private ObjectCollection<ChunkMap.TrackedEntity> prts$skipVanillaEntityBroadcast(Int2ObjectMap<ChunkMap.TrackedEntity> instance) {
        if (!prts$experimentalOn() || prts$routeBFailed || this.nearbyEntityTracking.isEmpty()) {
            return instance.values();
        }
        @SuppressWarnings("unchecked")
        ObjectCollection<ChunkMap.TrackedEntity> empty =
            (ObjectCollection<ChunkMap.TrackedEntity>) (Object) ObjectLists.EMPTY_LIST;
        return empty;
    }

    /**
     * tick()V 末尾：在 Forge 的 updateChunkTracking 循环（区块刷新）已运行之后，执行 AreaMap 优化实体广播，
     * 替代上方被 @Redirect 空掉的实体循环。看门狗保护：异常或超时就本会话回退原版。
     */
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void prts$tickEntityTracking(CallbackInfo ci) {
        if (!prts$diagLogged) {
            prts$diagLogged = true;
            LOGGER.info("[PRTS-EntityTrack] DIAG gate={} routeBFailed={} nearbyEmpty={}",
                prts$experimentalOn(), prts$routeBFailed, this.nearbyEntityTracking.isEmpty());
        }
        if (!prts$experimentalOn() || prts$routeBFailed) {
            return; // 原版实体循环照常运行（上方 redirect 未拦截）
        }
        // areaMap 未维护（如运行时切开关、或 addEntity 钩子未命中）→ 回退原版，避免虚空
        if (this.nearbyEntityTracking.isEmpty()) {
            return;
        }
        final long start = System.nanoTime();
        try {
            this.nearbyEntityTracking.tick();
            final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            if (elapsedMs > prts$watchdogHardMs()) {
                prts$routeBFailed = true;
                LOGGER.error("[PRTS-EntityTrack] tick took {}ms (> {}ms) -> disabling this session, falling back to vanilla. detail: {}",
                        elapsedMs, prts$watchdogHardMs(), this.nearbyEntityTracking.debugInfo());
                LOGGER.error("[PRTS-EntityTrack] tick stall stacktrace", new RuntimeException("[PRTS-EntityTrack] tick stall"));
                return;
            }
            if (!prts$announced) {
                prts$announced = true;
                LOGGER.info("[PRTS-EntityTrack] enabled (spatial entity tracking, HariPlayer AreaMap port). {}", this.nearbyEntityTracking.debugInfo());
            }
            if ((prts$tickCount++ % 200) == 0) {
                // 周期性心跳降到 DEBUG，避免生产日志刷屏；需诊断时开 debug 级别即可看到。
                LOGGER.debug("[PRTS-EntityTrack] active: {} lastTick={}ms", this.nearbyEntityTracking.debugInfo(), elapsedMs);
                this.nearbyEntityTracking.resetChurn();
            } else if (elapsedMs > prts$watchdogSoftMs()) {
                LOGGER.warn("[PRTS-EntityTrack] slow tick: {}ms | {}", elapsedMs, this.nearbyEntityTracking.debugInfo());
            }
        } catch (Throwable t) {
            prts$routeBFailed = true;
            LOGGER.error("[PRTS-EntityTrack] tick failed, this session falls back to vanilla entity tracking. detail: " + this.nearbyEntityTracking.debugInfo(), t);
        }
    }

    /**
     * addEntity(Entity) 返回时，TrackedEntity 已建好并放入 entityMap → 注册进 AreaMap。
     * 原版 updatePlayers 初始广播仍照常执行（不屏蔽），实体立即可见；AreaMap 负责后续每 tick 的优化广播。
     */
    @Inject(method = "addEntity", at = @At("RETURN"))
    private void prts$onAddEntity(Entity entity, CallbackInfo ci) {
        if (!prts$experimentalOn() || prts$routeBFailed) return;
        final long start = System.nanoTime();
        ChunkMap.TrackedEntity trackedEntity = this.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            this.nearbyEntityTracking.addEntityTracker(trackedEntity);
        }
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // addEntity 钩子在 tick 看门狗保护范围之外，单独看门狗：spread 爆炸会冻结主线程导致客户端超时
        if (elapsedMs > prts$watchdogHardMs()) {
            prts$routeBFailed = true;
            LOGGER.error("[PRTS-EntityTrack] addEntity took {}ms for {} -> disabling this session, falling back to vanilla. detail: {}",
                    elapsedMs, entity, this.nearbyEntityTracking.debugInfo());
            LOGGER.error("[PRTS-EntityTrack] addEntity stall stacktrace", new RuntimeException("[PRTS-EntityTrack] addEntity stall"));
        }
    }

    /**
     * removeEntity(Entity) 头部：TrackedEntity 尚未从 entityMap 移除 → 先从 AreaMap 注销。
     * 原版 removeEntity 仍会调用 broadcastRemoved 通知客户端，实体正常消失。
     */
    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void prts$onRemoveEntity(Entity entity, CallbackInfo ci) {
        if (!prts$experimentalOn() || prts$routeBFailed) return;
        ChunkMap.TrackedEntity trackedEntity = this.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            this.nearbyEntityTracking.removeEntityTracker(trackedEntity);
        }
    }
}
