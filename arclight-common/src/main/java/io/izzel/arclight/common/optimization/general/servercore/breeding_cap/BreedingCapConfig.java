/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.breeding_cap;

/**
 * breeding-cap 配置（移植自 Wesley1808/ServerCore BreedingCapConfig）。
 * enabled 总开关；villagers/animals 各持一个 BreedingCap（limit/range/unlimitedHeight）。
 */
public class BreedingCapConfig {
    private final boolean enabled;
    private final BreedingCap villagers;
    private final BreedingCap animals;

    public BreedingCapConfig(boolean enabled, BreedingCap villagers, BreedingCap animals) {
        this.enabled = enabled;
        this.villagers = villagers;
        this.animals = animals;
    }

    public boolean enabled() {
        return enabled;
    }

    public BreedingCap villagers() {
        return villagers;
    }

    public BreedingCap animals() {
        return animals;
    }

    public static final BreedingCapConfig DISABLED =
            new BreedingCapConfig(false, new BreedingCap(-1, 0, false), new BreedingCap(-1, 0, false));
}
