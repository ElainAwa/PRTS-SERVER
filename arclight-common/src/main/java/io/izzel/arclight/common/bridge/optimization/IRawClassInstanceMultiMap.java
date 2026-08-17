/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.bridge.optimization;

import java.util.List;

/**
 * Exposes the raw backing lists of {@code ClassInstanceMultiMap} to the spatial-index hot path.
 * Vanilla keeps its unmodifiable wrappers; only queries protected by the section lock use these
 * raw lists and index loops.
 */
public interface IRawClassInstanceMultiMap<T> {

    List<T> prts$rawAllInstances();

    <S> List<S> prts$rawFind(Class<S> type);
}
