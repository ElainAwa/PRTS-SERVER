package io.izzel.arclight.common.optimization.general.servercore.ticking;

/** 每 tick 重置一次结冰/积雪计数器，替代逐区块 nextInt(16)（Airplane）。 */
public interface IServerLevel {

    void arclight$resetIceAndSnowTick();
}
