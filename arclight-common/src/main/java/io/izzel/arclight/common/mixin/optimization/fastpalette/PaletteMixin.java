package io.izzel.arclight.common.mixin.optimization.fastpalette;

import io.izzel.arclight.common.optimization.fastpalette.FastPalette;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Palette.class)
interface PaletteMixin<T> extends FastPalette<T> {

}
