/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;
import java.util.List;

/**
 * worldgen 线程安全包（M3，照搬清单 2）：{@code StructureTemplate} 集合同步化。
 *
 * <p>模板实例经 {@code StructureTemplateManager} 缓存跨维度/跨任务共享；
 * 原版 {@code palettes}/{@code entityInfoList} 是裸 ArrayList，加载期写入、
 * 并行放置期读取。同步包装为并发读提供 happens-before 保险（读多写少，
 * 无锁读热路径 {@code Palette.blocks} 不动——其 {@code cache} 原版已是
 * ConcurrentMap，blocks 列表加载后只读）。
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin_SyncCollections {

    @Mutable
    @Shadow
    @Final
    private List<StructureTemplate.Palette> palettes;

    @Mutable
    @Shadow
    @Final
    private List<StructureTemplate.StructureEntityInfo> entityInfoList;

    @Redirect(method = "<init>",
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                    target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;palettes:Ljava/util/List;"))
    private void prts$syncPalettes(StructureTemplate self, List<StructureTemplate.Palette> value) {
        this.palettes = Collections.synchronizedList(value);
    }

    @Redirect(method = "<init>",
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                    target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;entityInfoList:Ljava/util/List;"))
    private void prts$syncEntityInfoList(StructureTemplate self, List<StructureTemplate.StructureEntityInfo> value) {
        this.entityInfoList = Collections.synchronizedList(value);
    }
}
