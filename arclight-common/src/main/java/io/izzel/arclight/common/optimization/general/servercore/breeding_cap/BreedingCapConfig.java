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
