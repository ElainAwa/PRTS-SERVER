package io.izzel.arclight.common.optimization.general.nearbyplayers;

/**
 * 由 MixinChunkMap_NearbyIndex 实现，挂在 ChunkMap 上（每维度一个索引实例）。
 * 运行时 ChunkMap 实例经 mixin 合并后实现本接口；编译期经 (Object) 双转换取用。
 */
public interface NearbyPlayerIndexHolder {

    NearbyPlayerIndex luminara$getNearbyPlayerIndex();
}
