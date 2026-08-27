/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import net.minecraft.server.MinecraftServer;
import org.bukkit.craftbukkit.v.util.Waitable;

import java.util.concurrent.Callable;

/**
 * A5: 主线程同步 marshal 工具（封装 Arclight 既有的 queuedProcess+Waitable）。
 *
 * <p>用途：worker 线程需要「带返回值的 Bukkit/主线程语义调用」时,把任务投到主线程执行并阻塞等待结果。
 * <b>安全约束</b>:当前 barrier 模型下,维度/region worker 正被主线程 barrier 等待时调用本工具必然死锁
 * (worker 等主线程、主线程等 barrier)——因此在 {@link DimensionTickManager#inDimensionTick()} /
 * {@link RegionTickManager#inRegionTick()} 窗口内调用直接抛异常拒绝。
 * 本工具为未来「playerless 维度自排程 loop 模型」(主线程不再等 barrier)预留;
 * 当前可安全使用的场景:登录/连接线程、区块生成后台线程等不被 barrier 收口的工作线程。</p>
 */
public final class MainThreadMarshal {

    private MainThreadMarshal() {
    }

    /**
     * 同步 marshal 到主线程并等待结果。
     *
     * @throws IllegalStateException 在并行 barrier 窗口内调用(死锁风险,拒绝)
     */
    public static <T> T call(MinecraftServer server, Callable<T> task) throws Exception {
        if (server.isSameThread()) {
            return task.call();
        }
        if (DimensionTickManager.inDimensionTick() || RegionTickManager.inRegionTick()) {
            throw new IllegalStateException(
                    "MainThreadMarshal.call() from inside a parallel barrier window would deadlock");
        }
        Waitable<T> waitable = new Waitable<>() {
            @Override
            protected T evaluate() {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        ((MinecraftServerBridge) server).bridge$queuedProcess(waitable);
        try {
            return waitable.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    /** 无返回值版本。 */
    public static void run(MinecraftServer server, Runnable task) throws Exception {
        call(server, () -> {
            task.run();
            return null;
        });
    }
}
