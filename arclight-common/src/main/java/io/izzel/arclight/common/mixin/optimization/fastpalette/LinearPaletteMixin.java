package io.izzel.arclight.common.mixin.optimization.fastpalette;

import io.izzel.arclight.common.optimization.fastpalette.FastPalette;
import io.izzel.arclight.common.optimization.fastpalette.FastPaletteData;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LinearPalette.class)
abstract class LinearPaletteMixin<T> implements Palette<T>, FastPalette<T> {

    @Shadow
    @Final
    private T[] values;

    @Override
    public final T[] moonrise$getRawPalette(final FastPaletteData<T> container) {
        return this.values;
    }
}
