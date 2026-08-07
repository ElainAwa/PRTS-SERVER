package io.izzel.arclight.common.optimization.general.servercore;

/**
 * Bridge interface implemented by LivingEntityMixin_ServerAiStep.
 * Lets the region worker invoke the NeoForge server AI step (goalSelector /
 * targetSelector / brain scheduling), which vanilla moved out of Entity.tick
 * into the protected serverAiStep() (P3 v08 slice 4 + mob-AI fix).
 */
public interface LivingEntityServerAiStepAccess {

    void arclight$invokerServerAiStep();
}
