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
 * 主线程同步 marshal 工具(封装 queuedProcess+Waitable):worker 需要带返回值的
 * 主线程语义调用时投到主线程执行并阻塞等待;barrier 窗口内调用直接拒绝(死锁风险)。
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
