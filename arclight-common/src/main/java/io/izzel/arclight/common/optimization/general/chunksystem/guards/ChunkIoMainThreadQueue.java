/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem.guards;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * IO 反序列化事件主线程延迟队列（M2.2 主线程边界四件套 ③，C2ME
 * {@code ChunkIoMainThreadTaskUtils} 等价物）。
 *
 * <p>M2.2 把 {@code ChunkSerializer.read} 移出主线程后，反序列化内发射的
 * NeoForge 事件（{@code ChunkDataEvent.Load}）被捕获并入本队列：捕获面为
 * {@code ChunkSerializerMixin_IoEventCapture} 对 {@code NeoForge.EVENT_BUS}
 * 静态读的重定向（{@link ChunkIoEventCaptureBus} 包装总线；对 bootstrap 层
 * {@code EventBus} 直接 mixin 永不生效，见 {@code EventBusStats} javadoc），
 * 由主线程 {@link #drain()} 重放，语义与基线「主线程反序列化期间发射」等价。
 *
 * <p>时序保证：某块的 EMPTY 加载由主线程发起（{@code ChunkMap.applyStep}
 * EMPTY 分支），其 future 结算回调经 {@code mainThreadExecutor} 排在后续
 * 主线程事务；本队列在每轮 {@code tickChunks} 末尾（主线程）排空，故事件
 * 重放不晚于「下一次 drain」、且不早于基线的「反序列化期间」相对序。
 */
public final class ChunkIoMainThreadQueue {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSystem");
    private static final ConcurrentLinkedQueue<Runnable> PENDING = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<Boolean> CAPTURE = ThreadLocal.withInitial(() -> false);
    /** M2.3 遥测：捕获计数、排水计数、排队等待纳秒累计（入队→排水）。 */
    private static final LongAdder CAPTURED = new LongAdder();
    private static final LongAdder DRAINED = new LongAdder();
    private static final LongAdder WAIT_NANOS = new LongAdder();

    private record Timed(Runnable task, long enqueuedAtNanos) implements Runnable {
        @Override
        public void run() {
            WAIT_NANOS.add(System.nanoTime() - enqueuedAtNanos);
            task.run();
        }
    }

    private ChunkIoMainThreadQueue() {
    }

    /** 捕获作用域：IO 线程反序列化期间置位，事件 post 改入队。 */
    public static boolean isCapturing() {
        return CAPTURE.get();
    }

    public static void beginCapture() {
        CAPTURE.set(true);
    }

    public static void endCapture() {
        CAPTURE.remove();
    }

    public static void enqueue(Runnable task) {
        CAPTURED.increment();
        PENDING.add(new Timed(task, System.nanoTime()));
    }

    /** M2.3 遥测：捕获（入队）事件总数。 */
    public static long captured() {
        return CAPTURED.sum();
    }

    /** M2.3 遥测：主线程已排水事件总数。 */
    public static long drained() {
        return DRAINED.sum();
    }

    /** M2.3 遥测：排队等待均值（ms），无样本返回 0。 */
    public static double waitAvgMs() {
        long drained = DRAINED.sum();
        return drained == 0 ? 0 : WAIT_NANOS.sum() / (double) drained / 1_000_000.0;
    }

    /** 主线程排空（{@code ServerChunkCache.tickChunks} 尾部调用）。 */
    public static void drain() {
        Runnable task;
        while ((task = PENDING.poll()) != null) {
            DRAINED.increment();
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.error("[chunk-system] deferred io event task failed", t);
            }
        }
    }
}
