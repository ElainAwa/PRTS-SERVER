/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.minecolonies.core.entity.citizen.EntityCitizen;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MineColonies 市民死亡处理涉及殖民地数据与墓碑方块实体，必须主线程执行：
 * 外部投射物在 region worker 上触发死亡时，把整个 die 推迟到主线程。
 */
@LoadIfMod(modid = "minecolonies", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = EntityCitizen.class, remap = false)
public abstract class EntityCitizenMixin_MainThreadDeath {

    @Inject(method = "die", at = @At("HEAD"), cancellable = true, remap = false)
    private void arclight$deferDeathToMainThread(DamageSource source, CallbackInfo ci) {
        EntityCitizen self = (EntityCitizen) (Object) this;
        if (self.level() instanceof ServerLevel serverLevel && !serverLevel.getServer().isSameThread()) {
            serverLevel.getServer().execute(() -> self.die(source));
            ci.cancel();
        }
    }
}
