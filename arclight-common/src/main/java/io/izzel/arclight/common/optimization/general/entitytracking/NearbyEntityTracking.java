package io.izzel.arclight.common.optimization.general.entitytracking;

import io.izzel.arclight.common.bridge.core.world.server.ChunkMap_TrackedEntityBridge;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [Luminara 本服维护者移植 2026-07-21]
 * 原 VMP com.ishland.vmp.common.playerwatching.NearbyEntityTracking 的 mojmap 移植。
 * 空间化实体追踪引擎：用 AreaMap 把实体按 chunk 坐标分桶，
 * tick 时只遍历视野范围内的桶，替代原版 O(实体×玩家) 全扫描。
 *
 * 相对原版的裁剪：
 *  - 移除 ValkyrienSkies 飞船坐标变换兼容（本服未装该 mod）。
 *  - 复用 Arclight 已有 ChunkMap_TrackedEntityBridge（bridge$getEntity/getServerEntity/getLastSectionPos/setLastSectionPos）。
 *  - staging area（短命实体暂存区）默认关闭（USE_STAGING_AREA=false），所有实体走 AreaMap 路径，降低移植风险；
 *    如需启用，把该常量改为 true 即可（对应原版 OPTIMIZED_ENTITY_TRACKING_USE_STAGING_AREA 默认 true）。
 */
public class NearbyEntityTracking {

    // 默认关闭 staging area，降低风险；改为 true 即启用原版短命实体暂存优化
    private static final boolean USE_STAGING_AREA = false;

    public static void init() {
        // intentionally empty
    }

    private final SimpleObjectPool<ReferenceLinkedOpenHashSet<?>> pooledHashSets =
            new SimpleObjectPool<>(unused -> new ReferenceLinkedOpenHashSet<>(),
                    ReferenceLinkedOpenHashSet::clear,
                    ts -> {
                        ts.clear();
                        ts.trim(256);
                    },
                    8192
            );

    // AreaMap implementation for long-lived entities
    private final AreaMap<ChunkMap.TrackedEntity> areaMap = new AreaMap<>();
    private final Reference2ReferenceLinkedOpenHashMap<ServerPlayer, ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>> playerTrackers = new Reference2ReferenceLinkedOpenHashMap<>();
    private final Reference2LongOpenHashMap<ChunkMap.TrackedEntity> tracker2ChunkPos = new Reference2LongOpenHashMap<>();

    // 诊断：自上次周期日志以来 routeB 给玩家 add/remove 的实体计数（"环带振荡"指标）。
    // 静止或正常移动时这两个数应很小（仅真实进出视野的实体）；
    // 若仍飙到上千，说明半径仍有偏差、振荡未消除。
    private long churnAdds = 0;
    private long churnRemoves = 0;
    // 诊断：自上次周期日志以来 routeB 因"瞬移/大跳变"跳过自身 diff、改由原版 move() 接管的次数
    private long teleportSkips = 0;

    // vanilla-like implementation for short-lived entities (staging area)
    private static final int STAGING_TRACKER_LIFETIME = 200; // 10s
    private final AtomicLong ticks = new AtomicLong(0L);
    private final ObjectLinkedOpenHashSet<StagedTracker> stagingTrackers = new ObjectLinkedOpenHashSet<>();

    private void addEntityTrackerAreaMap(ChunkMap.TrackedEntity tracker) {
        final ChunkPos pos = getEntityChunkPos(((ChunkMap_TrackedEntityBridge) tracker).bridge$getEntity());
        this.areaMap.add(
                tracker,
                pos.x,
                pos.z,
                getChunkViewDistance(tracker)
        );
        this.tracker2ChunkPos.put(tracker, ChunkPos.asLong(pos.x, pos.z));
    }

    public void addEntityTracker(ChunkMap.TrackedEntity tracker) {
        if (((ChunkMap_TrackedEntityBridge) tracker).bridge$getEntity() instanceof ServerPlayer player) {
            this.addPlayer(player);
        }
        if (USE_STAGING_AREA) {
            stagingTrackers.addAndMoveToLast(new StagedTracker(tracker, ticks.get()));
            for (ServerPlayer player : this.playerTrackers.keySet()) {
                tracker.updatePlayer(player);
            }
        } else {
            this.addEntityTrackerAreaMap(tracker);
        }
    }

    public void removeEntityTracker(ChunkMap.TrackedEntity tracker) {
        if (((ChunkMap_TrackedEntityBridge) tracker).bridge$getEntity() instanceof ServerPlayer player) {
            this.removePlayer(player);
        }

        // remove from staging
        if (this.stagingTrackers.remove(new StagedTracker(tracker, 0L))) {
            tracker.broadcastRemoved();
        }

        // remove from AreaMap
        this.areaMap.remove(tracker);
        this.tracker2ChunkPos.removeLong(tracker);
    }

    public void addPlayer(ServerPlayer player) {
        this.playerTrackers.put(player, (ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>) this.pooledHashSets.alloc());
    }

    public void removePlayer(ServerPlayer player) {
        // remove player in staging
        for (StagedTracker stagingTracker : this.stagingTrackers) {
            stagingTracker.tracker().removePlayer(player);
        }

        // remove player in AreaMap
        final ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity> originalTrackers = this.playerTrackers.remove(player);
        if (originalTrackers != null) {
            for (ChunkMap.TrackedEntity tracker : originalTrackers) {
                tracker.removePlayer(player);
            }
            this.pooledHashSets.release(originalTrackers);
        }
    }

    // 供 ChunkMap_TrackingMixin 兜底判断与诊断使用
    public boolean isEmpty() {
        return this.playerTrackers.isEmpty() && this.tracker2ChunkPos.isEmpty();
    }

    public int playerCount() {
        return this.playerTrackers.size();
    }

    public int trackedCount() {
        return this.tracker2ChunkPos.size();
    }

    // 由 ChunkMap_TrackingMixin 在打印周期日志后调用，重置 churn 计数
    public void resetChurn() {
        this.churnAdds = 0;
        this.churnRemoves = 0;
        this.teleportSkips = 0;
    }

    // 诊断用：返回每个玩家的"附近实体数 / 已追踪数"以及 AreaMap 占用，便于排查 tick 卡顿或视野异常
    public String debugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("players=").append(this.playerTrackers.size())
          .append(" tracked=").append(this.tracker2ChunkPos.size())
          .append(" areaCells=").append(this.areaMap.cellCount())
          .append(" churn=+").append(this.churnAdds).append("/-").append(this.churnRemoves)
          .append(" tpSkip=").append(this.teleportSkips);
        for (var entry : this.playerTrackers.entrySet()) {
            final Set<ChunkMap.TrackedEntity> cur = this.areaMap.getObjectsInRange(
                    ChunkPos.asLong(getEntityChunkPos(entry.getKey()).x, getEntityChunkPos(entry.getKey()).z));
            sb.append(" | p[")
              .append(((ServerPlayer) entry.getKey()).getName().getString())
              .append("] near=").append(cur.size())
              .append(" tracked=").append(entry.getValue().size());
        }
        return sb.toString();
    }

    private final ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity> trackerTickList = new ReferenceLinkedOpenHashSet<>() {
        @Override
        protected void rehash(int newN) {
            if (this.n < newN) {
                super.rehash(newN);
            }
        }
    };

    private static ChunkPos getEntityChunkPos(Entity entity) {
        Vec3 pos = entity.position();
        return new ChunkPos(SectionPos.blockToSectionCoord((int) pos.x), SectionPos.blockToSectionCoord((int) pos.z));
    }

    public void tick() {
        tickStaging();

        for (Reference2LongMap.Entry<ChunkMap.TrackedEntity> entry : this.tracker2ChunkPos.reference2LongEntrySet()) {
            final ChunkPos pos = getEntityChunkPos(((ChunkMap_TrackedEntityBridge) entry.getKey()).bridge$getEntity());
            if (ChunkPos.asLong(pos.x, pos.z) != entry.getLongValue()) {
                this.areaMap.update(entry.getKey(), pos.x, pos.z, getChunkViewDistance(entry.getKey()));
                entry.setValue(ChunkPos.asLong(pos.x, pos.z));
            }
        }

        trackerTickList.clear();

        for (var entry : this.playerTrackers.entrySet()) {
            final Set<ChunkMap.TrackedEntity> currentTrackers = this.areaMap.getObjectsInRange(ChunkPos.asLong(getEntityChunkPos(entry.getKey()).x, getEntityChunkPos(entry.getKey()).z));

            boolean isPlayerPositionUpdated = ((ServerPlayerEntityExtension) entry.getKey()).vmpTracking$isPositionUpdated();
            boolean isTeleport = ((ServerPlayerEntityExtension) entry.getKey()).vmpTracking$isTeleport();
            ((ServerPlayerEntityExtension) entry.getKey()).vmpTracking$updatePosition();

            final ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity> trackers = entry.getValue();

            // 瞬移 / 大距离跳变（/tp、waystones、tpmaster、传送门）：本 tick 完全交给原版 ChunkMap.move() 接管
            // 实体可见性——move() 已在新位置正确 addPairing/removePairing（顺序经原版验证不会冲垮客户端）。
            // routeB 只静默把自身记账集合同步到新位置，绝不发任何包，避免一次性对几百实体 add/remove 发包时
            // 顺序错乱 → 客户端 desync / 28s 冻结 / Timed out（关 routeB 后 tp 正常即证明此路径有问题）。
            if (isTeleport) {
                // 瞬移 / 大距离跳变（/tp、waystones、tpmaster、传送门）：本 tick 完全交给原版
                // ChunkMap.move() 接管实体可见性——原版已验证顺序安全（关 routeB 后 tp 正常即印证）。
                // routeB 只把自身对该玩家的记账重置为空白集（释放旧集、绝不向 seenBy 发任何包），
                // 避免旧位置实体在 routeB 侧泄漏、与新位置实体重叠导致客户端 desync / 冻结。
                // 下一 tick routeB 会在新位置正常重建记账；原版 move 已 add 的实体在 routeB 侧为 no-op，
                // 不会重复发包。
                // 关键：绝对不能像旧 resyncTrackers 那样"直接 put 替换记账集"——那样旧位置实体
                // 不会被 removePlayer，seenBy 持续泄漏，正是"第一次 tp 正常、第二次 tp 卡死"的根因。
                final ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity> fresh =
                        (ReferenceLinkedOpenHashSet<ChunkMap.TrackedEntity>) this.pooledHashSets.alloc();
                this.playerTrackers.put(entry.getKey(), fresh);
                this.pooledHashSets.release(trackers);
                this.teleportSkips++;
                continue;
            }

            // update original trackers — remove entities no longer in range
            for (ObjectListIterator<ChunkMap.TrackedEntity> iterator = trackers.iterator(); iterator.hasNext(); ) {
                ChunkMap.TrackedEntity entityTracker = iterator.next();
                if (currentTrackers.contains(entityTracker)) {
                    // Entity still in range — tick + optional position-update
                    if (trackerTickList.add(entityTracker)) {
                        tryTickTracker(entityTracker);
                    }
                    if (isPlayerPositionUpdated || ((EntityTrackerExtension) entityTracker).isPositionUpdated()) {
                        tryUpdateTracker(entityTracker, entry.getKey());
                    }
                } else {
                    // Entity out of range — remove
                    entityTracker.removePlayer(entry.getKey());
                    iterator.remove();
                    this.churnRemoves++;
                }
            }

            // add new trackers now in range
            for (ChunkMap.TrackedEntity entityTracker : currentTrackers) {
                if (!trackers.contains(entityTracker)) {
                    trackers.add(entityTracker);
                    this.churnAdds++;
                    if (trackerTickList.add(entityTracker)) {
                        tryTickTracker(entityTracker);
                    }
                    if (isPlayerPositionUpdated || ((EntityTrackerExtension) entityTracker).isPositionUpdated()) {
                        tryUpdateTracker(entityTracker, entry.getKey());
                    }
                }
            }
        }

        for (ChunkMap.TrackedEntity entityTracker : trackerTickList) {
            ((EntityTrackerExtension) entityTracker).updatePosition();
        }
    }

    private void tickStaging() {
        if (!USE_STAGING_AREA) return;

        // migrate staging trackers to AreaMap after lifetime expires
        final long currentTicks = this.ticks.incrementAndGet();
        for (ObjectListIterator<StagedTracker> iterator = this.stagingTrackers.iterator(); iterator.hasNext(); ) {
            StagedTracker stagingTracker = iterator.next();
            if (currentTicks - stagingTracker.tickAdded() >= STAGING_TRACKER_LIFETIME) {
                iterator.remove();
                addEntityTrackerAreaMap(stagingTracker.tracker());
            } else {
                break;
            }
        }

        // tick staging entities: update section positions and tick them
        final List<ServerPlayer> players = new ArrayList<>(this.playerTrackers.keySet());
        for (StagedTracker staged : this.stagingTrackers) {
            final ChunkMap.TrackedEntity entityTracker = staged.tracker();
            SectionPos chunkSectionPos = ((ChunkMap_TrackedEntityBridge) entityTracker).bridge$getLastSectionPos();
            final Entity entity = ((ChunkMap_TrackedEntityBridge) entityTracker).bridge$getEntity();
            SectionPos chunkSectionPos2 = SectionPos.of(entity);
            boolean bl = !Objects.equals(chunkSectionPos, chunkSectionPos2);
            if (bl) {
                for (ServerPlayer player : players) {
                    entityTracker.updatePlayer(player);
                }
                ((ChunkMap_TrackedEntityBridge) entityTracker).bridge$setLastSectionPos(chunkSectionPos2);
            }

            ((EntityTrackerExtension) entityTracker).tryTick();
        }

        for (StagedTracker staged : this.stagingTrackers) {
            for (ServerPlayer player : players) {
                staged.tracker().updatePlayer(player);
            }
        }
    }

    private static void tryUpdateTracker(ChunkMap.TrackedEntity entityTracker, ServerPlayer player) {
        entityTracker.updatePlayer(player);
    }

    private static void tryTickTracker(ChunkMap.TrackedEntity entityTracker) {
        ((EntityTrackerExtension) entityTracker).tryTick();
    }

    // 实体追踪半径上限（单位：chunk）。实体本就超不过加载区块半径，封顶可防止个别 mod 实体
    // 异常巨大的 clientTrackingRange 导致 AreaMap 爆炸式 spread（单次 add/update 遍历 (2*vd+1)^2 个桶）。
    // 原 VMP 不封顶，但本服 253 个模组环境需此防御；256 格(16 chunk)已覆盖绝大多数实体的 tracking range。
    private static final int MAX_VIEW_DISTANCE = 16;

    private int getChunkViewDistance(ChunkMap.TrackedEntity tracker) {
        // 追踪半径（单位 chunk）必须与 Forge 原版 PlayerChunkMap.move -> EntityTracker.updatePlayer 的有效半径
        // 【精确相等】，否则会出现"环带实体每 tick 反复 spawn/despawn"的刷包洪流，冻死客户端：
        //   - move 的 d0 = min(getEffectiveRange(), viewDistance*16)
        //   - getEffectiveRange = scaledRange(range)，scaledRange 默认恒等（= clientTrackingRange*16）
        //   - 本服 view-distance=10，对绝大多数实体（clientTrackingRange<=10）d0 = clientTrackingRange*16
        //     => 有效半径恰为 clientTrackingRange chunk（无 +1）
        //   - 若 routeB 用 clientTrackingRange+1，则比 move 宽 1 chunk；玩家移动时（isPlayerPositionUpdated=true）
        //     该环带实体被 routeB addPairing、又被 move removePairing，每 tick 刷出几千个实体包，
        //     客户端处理不过来 -> 回不了 keepalive -> 服务端判 Timed out（tp/跑图必现，站立不卡）。
        // 注意：VMP 原版在 Fabric 下用 +1 是因为 Fabric 的 vanilla updatePlayer 半径也带 +1（两边一致）；
        // Forge 的 move 不带 +1，故移植到 Forge 必须去掉 +1 才能对齐。
        final Entity entity = ((ChunkMap_TrackedEntityBridge) tracker).bridge$getEntity();
        final int clientTrackingRange = entity.getType().clientTrackingRange();
        // = ceil(clientTrackingRange*16/16) = clientTrackingRange（与 move 的 ceil(d0/16) 对齐）
        final int raw = clientTrackingRange;
        // 夹到 [1, MAX_VIEW_DISTANCE]，保证即使 range 异常（0/负数/巨大）也不会产生非法或爆炸的 vd
        return Math.max(1, Math.min(raw, MAX_VIEW_DISTANCE));
    }

    private record StagedTracker(ChunkMap.TrackedEntity tracker, long tickAdded) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StagedTracker that = (StagedTracker) o;
            return tracker == that.tracker;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(tracker);
        }
    }

}
