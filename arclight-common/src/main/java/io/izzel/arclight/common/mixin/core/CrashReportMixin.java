package io.izzel.arclight.common.mixin.core;

import io.izzel.arclight.api.ArclightVersion;
import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.common.optimization.general.chunksystem.ChunkSystemThreadState;
import net.minecraft.CrashReport;
import net.minecraft.SystemReport;
import org.bukkit.craftbukkit.v.CraftCrashReport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrashReport.class)
public class CrashReportMixin {

    @Shadow @Final private SystemReport systemReport;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void arclight$additional(String string, Throwable throwable, CallbackInfo ci) {
        this.systemReport.setDetail("PRTS Release", ArclightVersion.current()::getReleaseName);
        // M3 诊断（§4.4）：区块系统 worker 任务栈，崩溃时直接给出「哪块哪个状态步」
        this.systemReport.setDetail("PRTS ChunkSystem TaskStack", ChunkSystemThreadState::dump);
        if (ArclightServer.isInitialized()) {
            this.systemReport.setDetail("PRTS", new CraftCrashReport());
        } else {
            this.systemReport.setDetail("Arclight", "The crash happens before the server initialization.");
        }
    }
}
