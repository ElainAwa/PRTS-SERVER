package io.izzel.arclight.common.optimization.general.entitytracking;

/**
 * [Luminara 本服维护者移植 2026-07-21]
 * 原 VMP com.ishland.vmp.common.playerwatching.ServerPlayerEntityExtension 的 mojmap 版。
 * 由 ServerPlayer_TrackingMixin 实现，挂在 ServerPlayer 上。
 */
public interface ServerPlayerEntityExtension {

    boolean vmpTracking$isPositionUpdated();

    void vmpTracking$updatePosition();

    /**
     * 判断玩家本 tick 是否发生了"瞬移/大距离跳变"（如 /tp、waystones、tpmaster、传送门）。
     * 用于让 routeB 在大跳变那一 tick 跳过自身实体追踪 diff，完全交给原版 ChunkMap.move() 接管，
     * 避免 routeB 一次性对几百个实体做 add/remove 发包时顺序错乱（客户端 desync 卡死）。
     * 基于上一 tick 与当前 chunk 坐标的切比雪夫距离；正常行走/飞行每 tick 变化远小于此阈值。
     */
    boolean vmpTracking$isTeleport();

}
