/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from FlowSched by ishland (RelativityMC)
 * (https://github.com/RelativityMC/FlowSched), licensed under MIT.
 * Original code Copyright (c) RelativityMC.
 */

package io.izzel.arclight.common.optimization.general.chunksystem.scheduler;

import io.izzel.arclight.common.optimization.general.chunksystem.ChunkSystemStats;
import io.izzel.arclight.common.optimization.general.chunksystem.ChunkSystemThreadState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行器 worker 线程（FlowSched WorkerThread 移植）：信号量等待任务，
 * 出队→占锁→执行→释放；异常时兜底释放锁并经 {@link Task#propagateException} 上报。
 */
public class WorkerThread extends Thread {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSystem Worker");

    private final ExecutorManager executorManager;
    private volatile boolean shutdown = false;

    public WorkerThread(ExecutorManager executorManager) {
        this.executorManager = executorManager;
    }

    @Override
    public void run() {
        main_loop:
        while (true) {
            this.executorManager.waitObj.acquireUninterruptibly();

            if (this.shutdown) {
                return;
            }
            while (!this.shutdown && !pollTasks()) {
                Thread.onSpinWait();
            }
        }
    }

    private boolean pollTasks() {
        Task task = this.executorManager.getGlobalWorkQueue().dequeue();
        if (task == null) {
            return false;
        }
        task.lockWaitMark(System.nanoTime());
        if (!this.executorManager.tryLock(task)) {
            ChunkSystemStats.lockTryFailed();
            return true; // polled
        }
        task.lockWaitAcquired(System.nanoTime());
        long busyStart = System.nanoTime();
        ChunkSystemThreadState.push(task.workLabel());
        try {
            AtomicBoolean released = new AtomicBoolean(false);
            try {
                task.run(() -> {
                    if (released.compareAndSet(false, true)) {
                        executorManager.releaseLocks(task);
                    }
                });
            } catch (Throwable t) {
                try {
                    if (released.compareAndSet(false, true)) {
                        executorManager.releaseLocks(task);
                    }
                } catch (Throwable t1) {
                    t.addSuppressed(t1);
                    LOGGER.error("Exception thrown while releasing locks", t);
                }
                try {
                    task.propagateException(t);
                } catch (Throwable t1) {
                    t.addSuppressed(t1);
                    LOGGER.error("Exception thrown while propagating exception", t);
                }
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("Exception thrown while executing task", t);
            return true;
        } finally {
            ChunkSystemThreadState.pop();
            ChunkSystemStats.workerBusy(System.nanoTime() - busyStart);
        }
    }

    public void shutdown() {
        shutdown = true;
    }

}
