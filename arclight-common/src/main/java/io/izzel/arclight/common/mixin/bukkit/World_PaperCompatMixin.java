package io.izzel.arclight.common.mixin.bukkit;

import net.minecraft.server.level.ServerLevel;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.CraftWorld;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = World.class, remap = false)
public interface World_PaperCompatMixin {

    // Paper API compatibility: provide World#getCoordinateScale() at runtime
    default double getCoordinateScale() {
        if (this instanceof CraftWorld craft) {
            ServerLevel level = craft.getHandle();
            return level.dimensionType().coordinateScale();
        }
        return 1.0D;
    }
}
