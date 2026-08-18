/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped entity worker-safety policy. When a region worker throws any
 * Throwable while ticking an entity class (not only world-access violations),
 * that class is marked unsafe and routed back to the main thread for the rest
 * of the server session. Mirrors {@link BlockEntityAffinity} for entities.
 */
public final class EntityAffinity {

    /** Entity class name threw on a worker → permanently main-thread for this session. */
    private static final ConcurrentHashMap<String, Boolean> UNSAFE = new ConcurrentHashMap<>();

    private EntityAffinity() {
    }

    /** True when this entity class must not run on a region worker. */
    public static boolean isUnsafe(String className) {
        return className != null && UNSAFE.containsKey(className);
    }

    /** Marks an entity class as worker-unsafe for the rest of the server session. */
    public static void markUnsafe(String className) {
        if (className == null || className.isEmpty()) {
            return;
        }
        UNSAFE.put(className, Boolean.TRUE);
    }

    /** One-line policy summary for /servercore status. */
    public static String statusText() {
        return "unsafe=" + UNSAFE.size();
    }
}
