/*
 * Adapted from Moonrise (https://github.com/Tuinity/Moonrise), GPL v3.
 * Copyright (c) Spottedleaf and contributors. See docs/THIRD-PARTY.md.
 */

package io.izzel.arclight.common.optimization.general.fastpalette;

public interface FastPaletteData<T> {

    public T[] moonrise$getPalette();

    public void moonrise$setPalette(final T[] palette);

}
