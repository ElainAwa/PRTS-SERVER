/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem.guards;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

import java.util.function.Consumer;

/**
 * IO 反序列化事件捕获包装总线（M2.2 主线程边界四件套 ③，M2.3 修正注入面）。
 *
 * <p>背景：{@code net.neoforged.bus.EventBus} 由 bootstrap 层在任何 arclight
 * mixin 配置装配前加载，对它的 {@code post} 做 mixin 注入永不生效
 * （{@code EventBusStats} javadoc 已实证记录）。改经游戏层类
 * {@code ChunkSerializer}：其 {@code read} 内 {@code NeoForge.EVENT_BUS}
 * 静态字段读被 {@code ChunkSerializerMixin_IoEventCapture} 重定向到本包装，
 * 捕获作用域内（IO 反序列化线程）的 {@code post} 改入
 * {@link ChunkIoMainThreadQueue} 延迟主线程重放，作用域外直通原总线。
 *
 * <p>与基线语义等价：基线在主线程反序列化期间发射；M2 把反序列化移出
 * 主线程后，事件同样只发射一次、落在主线程（{@code tickChunks} 尾部排水），
 * 监听器无重复执行、无线程迁移暴露。
 */
public final class ChunkIoEventCaptureBus implements IEventBus {

    private final IEventBus delegate;

    private ChunkIoEventCaptureBus(IEventBus delegate) {
        this.delegate = delegate;
    }

    /** 捕获作用域内返回包装实例，否则直通原总线（零包装开销路径）。 */
    public static IEventBus wrapIfCapturing(IEventBus bus) {
        return ChunkIoMainThreadQueue.isCapturing() ? new ChunkIoEventCaptureBus(bus) : bus;
    }

    @Override
    public <T extends Event> T post(T event) {
        ChunkIoMainThreadQueue.enqueue(() -> delegate.post(event));
        return event;
    }

    @Override
    public <T extends Event> T post(EventPriority priority, T event) {
        ChunkIoMainThreadQueue.enqueue(() -> delegate.post(priority, event));
        return event;
    }

    // ===== 注册面直通（反序列化期间不发生注册，仅为接口完备） =====

    @Override
    public void register(Object target) {
        delegate.register(target);
    }

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        delegate.addListener(consumer);
    }

    @Override
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        delegate.addListener(priority, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(priority, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> consumer) {
        delegate.addListener(priority, receiveCancelled, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(priority, receiveCancelled, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCancelled, Consumer<T> consumer) {
        delegate.addListener(receiveCancelled, consumer);
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(receiveCancelled, eventType, consumer);
    }

    @Override
    public void unregister(Object object) {
        delegate.unregister(object);
    }

    @Override
    public void start() {
        delegate.start();
    }
}
