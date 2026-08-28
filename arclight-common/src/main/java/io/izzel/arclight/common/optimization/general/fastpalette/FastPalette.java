/*
 * Adapted from Moonrise (https://github.com/Tuinity/Moonrise), GPL v3.
 * Copyright (c) Spottedleaf and contributors. See docs/THIRD-PARTY.md.
 */

package io.izzel.arclight.common.optimization.general.fastpalette;

public interface FastPalette<T> {

    public default T[] moonrise$getRawPalette(final FastPaletteData<T> src) {
        return null;
    }

}
