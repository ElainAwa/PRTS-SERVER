package io.izzel.arclight.common.optimization.general.nearbyplayers;

import io.izzel.arclight.common.optimization.general.maps.AreaMap;
import io.izzel.arclight.common.optimization.general.util.MCUtil;
import io.izzel.arclight.i18n.ArclightConfig;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;

/**
 * 空间最近玩家索引（NearbyPlayerIndex）— 1.21.1 移植版（自 1.20.1 Luminara，target 描述符逐一 javap 核验一致）。
 * <p>
 * 目的：加速 EntityGetter#getNearestPlayer / #hasNearbyAlivePlayer 的全玩家线性扫描
 * （Mob.checkDespawn 每生物每 tick 无界调用一次 → 数千生物 × 大量玩家 = 每 tick 数十万次距离计算）。
 * <p>
 * 设计原则（吸取 chunkwatching 教训）：
 * <ul>
 *   <li>vanilla 记账永远是权威 —— 索引只做旁路加速，不改任何发包/加载路径；</li>
 *   <li>数学可证零感知：桶半径 {@link #BUCKET_RADIUS_CHUNKS}=10 chunk，桶外玩家水平距离必 ≥ 161 格；
 *       只有当桶内最优解 ≤ {@link #GUARD_DIST}=144 格时才采纳（144 &lt; 161 ⇒ 必为全局最优），
 *       否则 fallback 原版全扫，绝不猜测；</li>
 *   <li>verify 双跑：观察期每次查询与原版对照，不一致 → WARN + 采用原版结果；</li>
 *   <li>异常自毒（poison）：任何一次异常 → 本会话永久回退原版，最坏情况 = 没加速；</li>
 *   <li>线程守卫：仅在维护线程（服务器主线程）上响应查询，其他线程一律原版。</li>
 * </ul>
 */
public final class NearbyPlayerIndex {

    public static final Logger LOGGER = LogManager.getLogger("Luminara-NPI");

    /** 桶半径（chunk）。桶外玩家与查询点水平距离 ≥ (R+1)*16-15 = 161 格。 */
    public static final int BUCKET_RADIUS_CHUNKS = 10;
    /** 采纳守卫（格）。144 < 161，且覆盖 despawn 硬距离 128 与刷怪笼默认 16。 */
    public static final double GUARD_DIST = 144.0;
    public static final double GUARD_DIST_SQR = GUARD_DIST * GUARD_DIST;

    private final AreaMap<ServerPlayer> areaMap = new AreaMap<>();
    private final Reference2LongOpenHashMap<ServerPlayer> lastChunk = new Reference2LongOpenHashMap<>();
    private volatile boolean poisoned = false;
    private volatile Thread ownerThread = null;

    private long mismatchCount = 0;
    private long lastMismatchWarn = 0;

    public NearbyPlayerIndex() {
        this.lastChunk.defaultReturnValue(Long.MIN_VALUE);
    }

    // ============ 配置（懒加载缓存；运行期不热更） ============

    private static final class Cfg {
        static final boolean ENABLED;
        static final boolean VERIFY;

        static {
            boolean e = false, v = true;
            try {
                var opt = ArclightConfig.spec().getOptimization();
                e = opt.isNearbyPlayerIndexEnabled();
                v = opt.isNearbyPlayerIndexVerify();
            } catch (Throwable t) {
                e = false;
            }
            ENABLED = e;
            VERIFY = v;
        }
    }

    public static boolean enabled() {
        return Cfg.ENABLED;
    }

    public static boolean verifyMode() {
        return Cfg.VERIFY;
    }

    /** 从 Level 定位本维度的索引实例；任何一步不满足 → null（调用方回退原版）。 */
    public static NearbyPlayerIndex of(Level level) {
        if (level instanceof ServerLevel serverLevel) {
            ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
            if ((Object) chunkMap instanceof NearbyPlayerIndexHolder holder) {
                return holder.luminara$getNearbyPlayerIndex();
            }
        }
        return null;
    }

    // ============ 维护（仅由 ChunkMap.updatePlayerStatus / move 的 TAIL 注入调用） ============

    public void addPlayer(ServerPlayer player) {
        if (poisoned || !checkOwnerThread()) return;
        try {
            int cx = SectionPos.blockToSectionCoord(player.getBlockX());
            int cz = SectionPos.blockToSectionCoord(player.getBlockZ());
            long key = MCUtil.getCoordinateKey(cx, cz);
            if (this.lastChunk.getLong(player) != Long.MIN_VALUE) {
                // 已跟踪（防御：重复 add） → 按移动处理
                movePlayerTo(player, cx, cz, key);
                return;
            }
            this.lastChunk.put(player, key);
            this.areaMap.add(player, cx, cz, BUCKET_RADIUS_CHUNKS);
        } catch (Throwable t) {
            poison(t);
        }
    }

    public void removePlayer(ServerPlayer player) {
        if (poisoned || !checkOwnerThread()) return;
        try {
            if (this.lastChunk.removeLong(player) != Long.MIN_VALUE) {
                this.areaMap.remove(player);
            }
        } catch (Throwable t) {
            poison(t);
        }
    }

    public void movePlayer(ServerPlayer player) {
        if (poisoned || !checkOwnerThread()) return;
        try {
            int cx = SectionPos.blockToSectionCoord(player.getBlockX());
            int cz = SectionPos.blockToSectionCoord(player.getBlockZ());
            movePlayerTo(player, cx, cz, MCUtil.getCoordinateKey(cx, cz));
        } catch (Throwable t) {
            poison(t);
        }
    }

    private void movePlayerTo(ServerPlayer player, int cx, int cz, long key) {
        long prev = this.lastChunk.getLong(player);
        if (prev == Long.MIN_VALUE) {
            // 未跟踪（防御：move 先于 add 到达） → 补 add
            this.lastChunk.put(player, key);
            this.areaMap.add(player, cx, cz, BUCKET_RADIUS_CHUNKS);
            return;
        }
        if (prev == key) return; // 未跨区块，零开销
        this.lastChunk.put(player, key);
        this.areaMap.update(player, cx, cz, BUCKET_RADIUS_CHUNKS);
    }

    /** 维护线程守卫：首次调用者成为 owner；其他线程调用维护 = 结构性危险 → 自毒。 */
    private boolean checkOwnerThread() {
        Thread current = Thread.currentThread();
        Thread owner = this.ownerThread;
        if (owner == null) {
            this.ownerThread = current;
            return true;
        }
        if (owner != current) {
            poison(new IllegalStateException(
                    "NPI maintenance from wrong thread: " + current.getName() + " (owner: " + owner.getName() + ")"));
            return false;
        }
        return true;
    }

    private void poison(Throwable t) {
        if (!this.poisoned) {
            this.poisoned = true;
            LOGGER.error("[Luminara-NPI] index poisoned, permanently falling back to vanilla for this session", t);
        }
    }

    // ============ 查询（由 Mob.checkDespawn / BaseSpawner.isNearPlayer 的 @Redirect 调用） ============

    private boolean queryUsable() {
        return !this.poisoned && this.ownerThread == Thread.currentThread();
    }

    /**
     * 替代 Level.getNearestPlayer(Entity, double)（vanilla 语义：NO_SPECTATORS 过滤，
     * maxDist&lt;0 = 无界）。桶内命中且 ≤ 守卫 → 数学上必为全局最近；否则 fallback 原版。
     */
    public Player getNearestPlayer(Level level, Entity entity, double maxDist) {
        if (!queryUsable()) {
            return level.getNearestPlayer(entity, maxDist);
        }
        ServerPlayer fast;
        try {
            fast = nearestInBucket(entity.getX(), entity.getY(), entity.getZ(), maxDist);
        } catch (Throwable t) {
            poison(t);
            return level.getNearestPlayer(entity, maxDist);
        }
        if (fast == null) {
            // 桶内无合格玩家 / 最优解超守卫 → 原版全扫（天然正确）
            return level.getNearestPlayer(entity, maxDist);
        }
        if (Cfg.VERIFY) {
            Player vanilla = level.getNearestPlayer(entity, maxDist);
            if (vanilla != fast) {
                warnMismatch("getNearestPlayer", fast, vanilla);
                return vanilla;
            }
            return vanilla;
        }
        return fast;
    }

    /**
     * 替代 Level.hasNearbyAlivePlayer(x,y,z,range)（vanilla 语义：NO_SPECTATORS 且
     * LIVING_ENTITY_STILL_ALIVE，distSqr &lt; range²）。range ∈ [0, 守卫] 时桶即全集，
     * 命中与未命中均为确定性答案；range 越界 → fallback 原版。
     */
    public boolean hasNearbyAlivePlayer(Level level, double x, double y, double z, double range) {
        if (!queryUsable() || range < 0.0D || range > GUARD_DIST) {
            return level.hasNearbyAlivePlayer(x, y, z, range);
        }
        boolean fast;
        try {
            fast = hasAliveInBucket(x, y, z, range);
        } catch (Throwable t) {
            poison(t);
            return level.hasNearbyAlivePlayer(x, y, z, range);
        }
        if (Cfg.VERIFY) {
            boolean vanilla = level.hasNearbyAlivePlayer(x, y, z, range);
            if (vanilla != fast) {
                warnMismatch("hasNearbyAlivePlayer", fast, vanilla);
                return vanilla;
            }
            return vanilla;
        }
        return fast;
    }

    /** 桶内最近合格玩家；桶空 / 无合格 / 最优解超守卫 → null（调用方 fallback）。 */
    private ServerPlayer nearestInBucket(double x, double y, double z, double maxDist) {
        long key = MCUtil.getCoordinateKey(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        Set<ServerPlayer> bucket = this.areaMap.getObjectsInRange(key);
        double best = -1.0D;
        ServerPlayer bestPlayer = null;
        for (ServerPlayer p : bucket) {
            if (!EntitySelector.NO_SPECTATORS.test(p)) continue;
            double d = p.distanceToSqr(x, y, z);
            if ((maxDist < 0.0D || d < maxDist * maxDist) && (best == -1.0D || d < best)) {
                best = d;
                bestPlayer = p;
            }
        }
        if (bestPlayer == null || best > GUARD_DIST_SQR) return null;
        return bestPlayer;
    }

    private boolean hasAliveInBucket(double x, double y, double z, double range) {
        long key = MCUtil.getCoordinateKey(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        Set<ServerPlayer> bucket = this.areaMap.getObjectsInRange(key);
        double rangeSqr = range * range;
        for (ServerPlayer p : bucket) {
            if (!EntitySelector.NO_SPECTATORS.test(p)) continue;
            if (!EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(p)) continue;
            if (p.distanceToSqr(x, y, z) < rangeSqr) return true;
        }
        return false; // range ≤ 守卫 ⇒ 桶即全集，false 是确定性答案
    }

    private void warnMismatch(String what, Object fast, Object vanilla) {
        this.mismatchCount++;
        long now = System.currentTimeMillis();
        if (now - this.lastMismatchWarn > 10_000L) {
            this.lastMismatchWarn = now;
            LOGGER.warn("[Luminara-NPI] verify mismatch #{} in {}: index={}, vanilla={} (using vanilla result)",
                    this.mismatchCount, what, fast, vanilla);
        }
    }
}
