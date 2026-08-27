/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from FlowSched by ishland (RelativityMC)
 * (https://github.com/RelativityMC/FlowSched), licensed under MIT.
 * Original code Copyright (c) RelativityMC.
 */

package io.izzel.arclight.common.optimization.general.chunksystem.scheduler;

import java.util.Objects;

/**
 * 无锁简单任务（FlowSched SimpleTask 移植）：包装 Runnable，执行完释放（无锁可释放）。
 */
public class SimpleTask implements Task {

    private final Runnable wrapped;
    private final int priority;

    public SimpleTask(Runnable wrapped, int priority) {
        this.wrapped = Objects.requireNonNull(wrapped);
        this.priority = priority;
    }

    @Override
    public void run(Runnable releaseLocks) {
        try {
            wrapped.run();
        } finally {
            releaseLocks.run();
        }
    }

    @Override
    public void propagateException(Throwable t) {
        org.apache.logging.log4j.LogManager.getLogger("PRTS-ChunkSystem").error("Exception in scheduled task", t);
    }

    @Override
    public LockToken[] lockTokens() {
        return new LockToken[0];
    }

    @Override
    public int priority() {
        return this.priority;
    }
}
