/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

/**
 * Exposes the vanilla {@code Level.thread} field (the owning main thread) so
 * region code applies the exact same thread check Level.getBlockEntity uses.
 */
public interface LevelMainThreadAccess {

    /** Returns the thread that owns this level (vanilla Level.thread). */
    Thread arclight$getMainThread();
}
