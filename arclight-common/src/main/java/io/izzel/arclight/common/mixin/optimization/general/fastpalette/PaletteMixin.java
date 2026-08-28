package io.izzel.arclight.common.mixin.optimization.general.fastpalette;

import io.izzel.arclight.common.optimization.general.fastpalette.FastPalette;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Palette.class)
interface PaletteMixin<T> extends FastPalette<T> {

}
