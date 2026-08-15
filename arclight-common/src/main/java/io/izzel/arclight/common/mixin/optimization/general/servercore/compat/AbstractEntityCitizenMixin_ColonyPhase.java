/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * MineColonies 市民工作 AI 的 5-tick 门槛按 citizenId 错峰，避免全殖民地
 * 同 tick 集中执行 entityStateController.tick()。
 */
@LoadIfMod(modid = "minecolonies", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = AbstractEntityCitizen.class, remap = false)
public abstract class AbstractEntityCitizenMixin_ColonyPhase {

    @Redirect(method = "aiStep", remap = false,
            at = @At(value = "FIELD", target = "tickCount:I", opcode = Opcodes.GETFIELD))
    private int arclight$colonyPhaseShift(AbstractEntityCitizen self) {
        if (!PRTSFeaturesConfig.parallelColonyPhaseStagger) {
            return self.tickCount;
        }
        return self.tickCount + (self.getId() & 0x7FFFFFFF) % 5;
    }
}
