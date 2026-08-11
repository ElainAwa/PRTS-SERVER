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

/** 空间化实体追踪（AreaMap 方案，移植自 VMP 同名算法）的 mojmap 移植。 */
@Mixin(ChunkMap.class)
public class ChunkMap_TrackingMixin {

    // @formatter:off
    @Shadow @Final private Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;
    // @formatter:on

    @Unique
    private static final Logger LOGGER = LogManager.getLogger("PRTS-EntityTrack");

    // AreaMap 内部加锁（NearbyEntityTracking.lock），区域 worker 直接执行
    // add/remove 追踪更新（实时，无 1 tick 延迟）；不再需要延迟队列止血。

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

    /** 重定向 tick()V 里的实体广播循环（entityMap.values() 第一处遍历；ordinal=0 精确拦截主广播循环）。 */
    @Redirect(method = "tick()V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;", ordinal = 0))
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
        final ChunkMap.TrackedEntity trackedEntity = this.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            final long start = System.nanoTime();
            this.nearbyEntityTracking.addEntityTracker(trackedEntity);
            final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            // addEntity 钩子在 tick 看门狗保护范围之外，单独看门狗：spread 爆炸会冻结主线程导致客户端超时
            if (elapsedMs > prts$watchdogHardMs()) {
                prts$routeBFailed = true;
                LOGGER.error("[PRTS-EntityTrack] addEntity took {}ms for {} -> disabling this session, falling back to vanilla. detail: {}",
                        elapsedMs, entity, this.nearbyEntityTracking.debugInfo());
                LOGGER.error("[PRTS-EntityTrack] addEntity stall stacktrace", new RuntimeException("[PRTS-EntityTrack] addEntity stall"));
            }
        }
    }

    /**
     * removeEntity(Entity) 头部：TrackedEntity 尚未从 entityMap 移除 → 先从 AreaMap 注销。
     * 原版 removeEntity 仍会调用 broadcastRemoved 通知客户端，实体正常消失。
     */
    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void prts$onRemoveEntity(Entity entity, CallbackInfo ci) {
        if (!prts$experimentalOn() || prts$routeBFailed) return;
        final ChunkMap.TrackedEntity trackedEntity = this.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            this.nearbyEntityTracking.removeEntityTracker(trackedEntity);
        }
    }
}
