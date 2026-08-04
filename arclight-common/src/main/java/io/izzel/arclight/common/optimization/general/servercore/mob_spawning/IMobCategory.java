package io.izzel.arclight.common.optimization.general.servercore.mob_spawning;

import net.minecraft.world.entity.MobCategory;

import java.util.List;

// MobCategory 的 duck interface，由 MobCategoryMixin 实现（移植自 ServerCore IMobCategory）。
public interface IMobCategory {
    int servercore$getSpawnInterval();

    int servercore$getOriginalCapacity();

    void servercore$modifyCapacity(double modifier);

    void servercore$modifySpawningConfig(MobSpawnEntry config);

    void servercore$restore();

    static IMobCategory of(MobCategory category) {
        return (IMobCategory) (Object) category;
    }

    static int getSpawnInterval(MobCategory category) {
        return IMobCategory.of(category).servercore$getSpawnInterval();
    }

    static int getOriginalCapacity(MobCategory category) {
        return IMobCategory.of(category).servercore$getOriginalCapacity();
    }

    static void modifyCapacity(MobCategory category, double modifier) {
        IMobCategory.of(category).servercore$modifyCapacity(modifier);
    }

    // modifySpawningConfig 只改 max，须补一次 modifyCapacity(1.0) 让缓存的 modifiedCapacity 跟上。
    static void apply(List<MobSpawnEntry> entries) {
        for (MobSpawnEntry entry : entries) {
            IMobCategory category = IMobCategory.of(entry.category());
            category.servercore$modifySpawningConfig(entry);
            category.servercore$modifyCapacity(1.0D);
        }
    }

    // 总开关关闭时还原全部 MobCategory 修改，避免 mobcap 残留。
    static void restoreAll() {
        for (MobCategory category : MobCategory.values()) {
            IMobCategory.of(category).servercore$restore();
        }
    }
}
