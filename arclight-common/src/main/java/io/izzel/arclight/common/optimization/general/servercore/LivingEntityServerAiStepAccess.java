/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

/**
 * Bridge interface implemented by LivingEntityMixin_ServerAiStep, letting the
 * region worker invoke the NeoForge server AI step that vanilla moved out of
 * Entity.tick into the protected serverAiStep().
 */
public interface LivingEntityServerAiStepAccess {

    void arclight$invokerServerAiStep();
}
