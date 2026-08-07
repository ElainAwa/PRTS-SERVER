package io.izzel.arclight.common.mixin.optimization.general.servercore.misc;

import com.google.common.collect.Sets;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig.Feature;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Target;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Ported from Wesley1808/ServerCore (Mojmap / 1.21.1).
@Mixin(PathFinder.class)
public class PathFinderMixin {
    @Shadow
    @Final
    private NodeEvaluator nodeEvaluator;

    @Redirect(
            method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Set;stream()Ljava/util/stream/Stream;"
            )
    )
    private Stream<?> servercore$reduceStreams(Set<?> set) {
        if (!ServerCoreConfig.isEnabled(Feature.PATHFINDING)) return set.stream();
        return null;
    }

    @Redirect(
            method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/stream/Collectors;toMap(Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/util/stream/Collector;"
            )
    )
    private Collector<?, ?, ?> servercore$reduceStreams(Function<?, ?> keyMapper, Function<?, ?> valueMapper) {
        if (!ServerCoreConfig.isEnabled(Feature.PATHFINDING)) return (Collector) Collectors.toMap((Function) keyMapper, (Function) valueMapper);
        return null;
    }

    @Redirect(
            method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"
            )
    )
    private Object servercore$reduceStreams(Stream<?> stream, Collector<?, ?, ?> collector) {
        if (!ServerCoreConfig.isEnabled(Feature.PATHFINDING)) return ((Stream) stream).collect((Collector) collector);
        return null;
    }

    @ModifyVariable(
            method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            index = 8,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/PathNavigationRegion;getProfiler()Lnet/minecraft/util/profiling/ProfilerFiller;",
                    shift = At.Shift.BEFORE
            )
    )
    private Map<Target, BlockPos> servercore$replaceMap(Map<Target, BlockPos> nullMap, PathNavigationRegion region, Mob mob, Set<BlockPos> positions, float maxRange, int accuracy, float searchDepthMultiplier) {
        if (!ServerCoreConfig.isEnabled(Feature.PATHFINDING)) return nullMap;
        Object2ObjectOpenHashMap<Target, BlockPos> map = new Object2ObjectOpenHashMap<>(positions.size());
        for (BlockPos pos : positions) {
            map.put(this.nodeEvaluator.getTarget(pos.getX(), pos.getY(), pos.getZ()), pos);
        }

        return map;
    }

    @Redirect(
            method = "findPath(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Sets;newHashSetWithExpectedSize(I)Ljava/util/HashSet;",
                    ordinal = 0,
                    remap = false
            )
    )
    private HashSet<?> servercore$noHashSet(int expectedSize) {
        if (!ServerCoreConfig.isEnabled(Feature.PATHFINDING)) return Sets.newHashSetWithExpectedSize(expectedSize);
        return null;
    }

    @ModifyVariable(
            method = "findPath(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            index = 10,
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/pathfinder/PathFinder;maxVisitedNodes:I",
                    opcode = Opcodes.GETFIELD,
                    ordinal = 0
            )
    )
    private Set<Target> servercore$replaceSet(Set<Target> nullSet, ProfilerFiller profiler, Node node, Map<Target, BlockPos> positions, float maxRange, int accuracy, float searchDepthMultiplier) {
        if (!ServerCoreConfig.isEnabled(Feature.PATHFINDING)) return nullSet;
        return new ObjectArraySet<>();
    }
}
