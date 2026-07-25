package io.izzel.arclight.common.mixin.optimization.general.entitytracking;

import io.izzel.arclight.common.optimization.general.entitytracking.NearbyEntityTracking;
import io.izzel.arclight.common.optimization.general.entitytracking.ServerPlayerEntityExtension;
import io.izzel.arclight.i18n.ArclightConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
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
 * [Luminara 本服维护者移植 2026-07-21]
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
    private static final Logger LOGGER = LogManager.getLogger("Luminara-EntityTrack");

    // 仅首次成功 tick 时打印一次启用通告（INFO），避免每次重启重复刷屏。
    @Unique
    private static boolean luminara$announced = false;
    @Unique
    private static boolean luminara$diagLogged = false;

    @Unique
    private final NearbyEntityTracking nearbyEntityTracking = new NearbyEntityTracking();

    @Unique
    private static boolean luminara$routeBFailed = false;
    @Unique
    private static int luminara$tickCount = 0;
    // 一次性日志：确认 move() 的实体遍历被 routeB 接管（seenBy 唯一管理者）
    @Unique
    private static boolean luminara$moveRedirLogged = false;
    // 看门狗阈值从 luminara.yml 的 optimization.route-b 读取（watchdog-hard-ms / watchdog-soft-ms），
    // 运行时由 config 提供，确保启动后读到的就是 yml 值。
    @Unique
    private static long luminara$watchdogHardMs() {
        return ArclightConfig.spec().getOptimization().getRouteB().getWatchdogHardMs();
    }
    @Unique
    private static long luminara$watchdogSoftMs() {
        return ArclightConfig.spec().getOptimization().getRouteB().getWatchdogSoftMs();
    }

    private static boolean luminara$experimentalOn() {
        return ArclightConfig.spec().getOptimization().isRouteBEnabled();
    }

    /**
     * 重定向 tick()V 里的实体广播循环（entityMap.values() 遍历）。
     * routeB 激活时返回空集合 → 原版实体广播（updatePlayers/sendChanges）被跳过，由下方 AreaMap 接管；
     * 同时 tick()V 顶部的 updateChunkTracking 循环完全不受影响 → 区块刷新照常。
     * routeB 未激活 / 已失败 / AreaMap 未维护时返回真实集合 → 原版实体广播照常运行。
     */
    @Redirect(method = "tick()V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"))
    private ObjectCollection<ChunkMap.TrackedEntity> luminara$skipVanillaEntityBroadcast(Int2ObjectMap<ChunkMap.TrackedEntity> instance) {
        if (!luminara$experimentalOn() || luminara$routeBFailed || this.nearbyEntityTracking.isEmpty()) {
            return instance.values();
        }
        @SuppressWarnings("unchecked")
        ObjectCollection<ChunkMap.TrackedEntity> empty =
            (ObjectCollection<ChunkMap.TrackedEntity>) (Object) ObjectLists.EMPTY_LIST;
        return empty;
    }

    /**
     * 重定向 move(ServerPlayer)V 里的实体遍历循环（entityMap.values()）。
     * 这是 routeB 成为 seenBy 唯一管理者的关键补全：原版 tick()V 的实体循环已被上方 @Redirect 空掉，
     * 但原版 move(ServerPlayer) 每 tick 仍遍历所有实体、用【欧氏距离】对边界实体做 addPairing/removePairing，
     * 与 routeB tick() 的【方格/Chebyshev】判定几何不一致 → 视距边界环带实体被两边争抢 seenBy
     * → 客户端反复收到出生包/移除包 → 物品随机消失又出现（群友反馈的"连锁挖矿随机爆东西"）。
     *
     * 本 @Redirect 在正常移动时把 move 的实体遍历空掉，让 routeB tick() 唯一维护 seenBy（判定统一，无振荡）；
     * 瞬移/大跳变（isTeleport）时放行原版 move（routeB tick() 也同步让位），沿用 teleport 安全路径。
     * 门控与原版 tick 一致：开关关闭/已失败/AreaMap 未维护时返回真实集合（原版照常）。
     */
    @Redirect(method = "move(Lnet/minecraft/server/level/ServerPlayer;)V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"))
    private ObjectCollection<ChunkMap.TrackedEntity> luminara$skipVanillaMoveBroadcast(Int2ObjectMap<ChunkMap.TrackedEntity> instance, ServerPlayer player) {
        if (!luminara$experimentalOn() || luminara$routeBFailed || this.nearbyEntityTracking.isEmpty()) {
            return instance.values();
        }
        // 瞬移/大跳变：放行原版 move（routeB tick() 让位），避免一次性对几百实体 add/remove 顺序错乱
        if (((ServerPlayerEntityExtension) player).vmpTracking$isTeleport()) {
            return instance.values();
        }
        if (!luminara$moveRedirLogged) {
            luminara$moveRedirLogged = true;
            LOGGER.info("[Luminara-EntityTrack] move() entity loop intercepted -> routeB is now sole owner of seenBy (no double-maintenance oscillation)");
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
    private void luminara$tickEntityTracking(CallbackInfo ci) {
        if (!luminara$diagLogged) {
            luminara$diagLogged = true;
            try {
                var opt = ArclightConfig.spec().getOptimization();
                LOGGER.info("[Luminara-EntityTrack] DIAG gate={} routeBFailed={} nearbyEmpty={} expOn={} routeB.isEnabled={} isRouteBEnabled={}",
                    luminara$experimentalOn(), luminara$routeBFailed, this.nearbyEntityTracking.isEmpty(),
                    opt.isExperimentalOptimizationsEnabled(), opt.getRouteB().isEnabled(), opt.isRouteBEnabled());
            } catch (Throwable t) {
                LOGGER.info("[Luminara-EntityTrack] DIAG error reading config: " + t);
            }
        }
        if (!luminara$experimentalOn() || luminara$routeBFailed) {
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
            if (elapsedMs > luminara$watchdogHardMs()) {
                luminara$routeBFailed = true;
                LOGGER.error("[Luminara-EntityTrack] tick took {}ms (> {}ms) -> disabling this session, falling back to vanilla. detail: {}",
                        elapsedMs, luminara$watchdogHardMs(), this.nearbyEntityTracking.debugInfo());
                LOGGER.error("[Luminara-EntityTrack] tick stall stacktrace", new RuntimeException("[Luminara-EntityTrack] tick stall"));
                return;
            }
            if (!luminara$announced) {
                luminara$announced = true;
                LOGGER.info("[Luminara-EntityTrack] enabled (spatial entity tracking, HariPlayer AreaMap port). {}", this.nearbyEntityTracking.debugInfo());
            }
            if ((luminara$tickCount++ % 200) == 0) {
                // 周期性心跳降到 DEBUG，避免生产日志刷屏；需诊断时开 debug 级别即可看到。
                LOGGER.debug("[Luminara-EntityTrack] active: {} lastTick={}ms", this.nearbyEntityTracking.debugInfo(), elapsedMs);
                this.nearbyEntityTracking.resetChurn();
            } else if (elapsedMs > luminara$watchdogSoftMs()) {
                LOGGER.warn("[Luminara-EntityTrack] slow tick: {}ms | {}", elapsedMs, this.nearbyEntityTracking.debugInfo());
            }
        } catch (Throwable t) {
            luminara$routeBFailed = true;
            LOGGER.error("[Luminara-EntityTrack] tick failed, this session falls back to vanilla entity tracking. detail: " + this.nearbyEntityTracking.debugInfo(), t);
        }
    }

    /**
     * addEntity(Entity) 返回时，TrackedEntity 已建好并放入 entityMap → 注册进 AreaMap。
     * 原版 updatePlayers 初始广播仍照常执行（不屏蔽），实体立即可见；AreaMap 负责后续每 tick 的优化广播。
     */
    @Inject(method = "addEntity", at = @At("RETURN"))
    private void luminara$onAddEntity(Entity entity, CallbackInfo ci) {
        if (!luminara$experimentalOn() || luminara$routeBFailed) return;
        final long start = System.nanoTime();
        ChunkMap.TrackedEntity trackedEntity = this.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            this.nearbyEntityTracking.addEntityTracker(trackedEntity);
        }
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // addEntity 钩子在 tick 看门狗保护范围之外，单独看门狗：spread 爆炸会冻结主线程导致客户端超时
        if (elapsedMs > luminara$watchdogHardMs()) {
            luminara$routeBFailed = true;
            LOGGER.error("[Luminara-EntityTrack] addEntity took {}ms for {} -> disabling this session, falling back to vanilla. detail: {}",
                    elapsedMs, entity, this.nearbyEntityTracking.debugInfo());
            LOGGER.error("[Luminara-EntityTrack] addEntity stall stacktrace", new RuntimeException("[Luminara-EntityTrack] addEntity stall"));
        }
    }

    /**
     * removeEntity(Entity) 头部：TrackedEntity 尚未从 entityMap 移除 → 先从 AreaMap 注销。
     * 原版 removeEntity 仍会调用 broadcastRemoved 通知客户端，实体正常消失。
     */
    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void luminara$onRemoveEntity(Entity entity, CallbackInfo ci) {
        if (!luminara$experimentalOn() || luminara$routeBFailed) return;
        ChunkMap.TrackedEntity trackedEntity = this.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            this.nearbyEntityTracking.removeEntityTracker(trackedEntity);
        }
    }
}
