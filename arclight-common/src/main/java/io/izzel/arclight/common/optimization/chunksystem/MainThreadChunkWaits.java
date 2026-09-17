/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.chunksystem;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主线程正在同步等待的区块登记表（维度 + 坐标 + 等待线程）。
 *
 * <p>生成预算据此对该区块放行：提交窗口被其它路径长期占满时，若连主线程阻塞
 * 等待的那个区块也排不上队，主线程就会一直停在 {@code managedBlock} 里
 * （tick 与 RCON 全部停摆）。登记表只由等待线程自己登记与注销，
 * 其它线程的同坐标读取不会误删（{@code remove(key, value)} 语义）。
 *
 * <p>读取路径（预算 mixin 每 ~100µs 扫一次）刻意不做字符串拼接：
 * {@link #waits()} 返回可直接按维度/坐标比较的记录集合。
 */
public final class MainThreadChunkWaits {

    /** 维度 + 坐标 + 等待者。 */
    public record Wait(ResourceKey<Level> dimension, int x, int z, Thread thread) {
    }

    private static final ConcurrentHashMap<String, Wait> AWAITED = new ConcurrentHashMap<>();

    /**
     * 最近一个登记等待的线程：{@link #end} 的快通道判据。登记只可能发生在
     * "既非维度 tick 线程、也非区域 worker"的线程（即服务端主线程），故实际只有一个；
     * 万一出现第二个，{@link #begin} 会清掉已死线程的残留位兜底。
     */
    private static volatile Thread lastWaiter;

    private MainThreadChunkWaits() {
    }

    /** 主线程开始等该区块。 */
    public static void begin(ServerLevel level, int x, int z) {
        if (!AWAITED.isEmpty()) {
            AWAITED.values().removeIf(wait -> !wait.thread().isAlive());
        }
        Thread self = Thread.currentThread();
        AWAITED.put(key(level, x, z), new Wait(level.dimension(), x, z, self));
        lastWaiter = self;
    }

    /** 等待结束；只有登记它的那个线程能注销。非等待线程调用时零分配返回。 */
    public static void end(ServerLevel level, int x, int z) {
        if (AWAITED.isEmpty() || lastWaiter != Thread.currentThread()) {
            return;
        }
        AWAITED.remove(key(level, x, z), new Wait(level.dimension(), x, z, Thread.currentThread()));
        if (AWAITED.isEmpty()) {
            lastWaiter = null;
        }
    }

    /** 当前所有等待位；无等待者时返回空集合（零分配）。 */
    public static Collection<Wait> waits() {
        return AWAITED.isEmpty() ? java.util.List.of() : AWAITED.values();
    }

    /** 该坐标是否正被某线程同步等待（无等待者时零分配快速返回）。 */
    public static boolean isAwaited(ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        return isAwaited(level.dimension(), pos);
    }

    /** 该维度该坐标是否正被某线程同步等待。 */
    public static boolean isAwaited(ResourceKey<Level> dimension, net.minecraft.world.level.ChunkPos pos) {
        if (AWAITED.isEmpty()) {
            return false;
        }
        for (Wait wait : AWAITED.values()) {
            if (wait.x() == pos.x && wait.z() == pos.z && wait.dimension().equals(dimension)) {
                return true;
            }
        }
        return false;
    }

    private static String key(ServerLevel level, int x, int z) {
        return level.dimension().location() + "|" + x + "|" + z;
    }
}
