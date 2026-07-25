package io.izzel.arclight.common.mixin.optimization.general.servercore.sync_loads;

import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Supplier;

// Ported from Wesley1808/ServerCore (via Luminara 1.20.1); Level ctor signature verified
// identical on 1.21.1 via javap.
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin extends Level {
    private ServerLevelMixin(WritableLevelData writableLevelData, ResourceKey<Level> resourceKey, RegistryAccess registryAccess, Holder<DimensionType> holder, Supplier<ProfilerFiller> supplier, boolean bl, boolean bl2, long l, int i) {
        super(writableLevelData, resourceKey, registryAccess, holder, supplier, bl, bl2, l, i);
    }

    // Don't load chunks for raytracing.
    @Override
    public BlockHitResult clip(ClipContext context) {
        Vec3 to = context.getTo();
        if (ChunkManager.hasChunk(this, Mth.floor(to.x) >> 4, Mth.floor(to.z) >> 4)) {
            return super.clip(context);
        } else {
            Vec3 vec3 = context.getFrom().subtract(to);
            return BlockHitResult.miss(to, Direction.getNearest(vec3.x, vec3.y, vec3.z), BlockPos.containing(to));
        }
    }
}
