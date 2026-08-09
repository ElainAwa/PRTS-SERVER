/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.activation_range;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.List;

/**
 * 带名称与实体匹配器的自定义激活类型（对应配置 custom-activation-types 的一项）。
 */
public class CustomActivationType extends ActivationType {

    private final String name;
    private final List<EntityTypeTest<? super Entity, ?>> matchers;

    public CustomActivationType(String name, int activationRange, int tickInterval, int wakeupInterval,
                                boolean extraHeightUp, boolean extraHeightDown,
                                List<EntityTypeTest<? super Entity, ?>> matchers) {
        super(activationRange, tickInterval, wakeupInterval, extraHeightUp, extraHeightDown);
        this.name = name;
        this.matchers = matchers;
    }

    public String name() {
        return this.name;
    }

    public List<EntityTypeTest<? super Entity, ?>> matchers() {
        return this.matchers;
    }
}
