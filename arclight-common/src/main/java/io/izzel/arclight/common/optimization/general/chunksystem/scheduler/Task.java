/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from FlowSched by ishland (RelativityMC)
 * (https://github.com/RelativityMC/FlowSched), licensed under MIT.
 * Original code Copyright (c) RelativityMC.
 */

package io.izzel.arclight.common.optimization.general.chunksystem.scheduler;

/**
 * 可调度任务（FlowSched Task 移植）。执行器在 worker 上调用 {@link #run(Runnable)}，
 * 任务必须在返回前（同步段结束或异步等待挂起时）调用 {@code releaseLocks} 释放持有的锁。
 */
public interface Task {

    void run(Runnable releaseLocks);

    void propagateException(Throwable t);

    LockToken[] lockTokens();

    int priority();

    /** 遥测：标记首次出队尝试时刻（已标记则无操作）。默认空实现，需要计时的任务自行覆盖。 */
    default void lockWaitMark(long nanos) {
    }

    /** 遥测：获得锁时回调，用于记录首次出队→获锁的全程等待时间。 */
    default void lockWaitAcquired(long nowNanos) {
    }

    /** 诊断（M3 ThreadState）：任务标签，记入执行线程的任务栈。 */
    default String workLabel() {
        return getClass().getSimpleName();
    }

}
