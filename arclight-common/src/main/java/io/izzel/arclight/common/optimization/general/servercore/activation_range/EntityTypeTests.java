/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.activation_range;

import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置项 'typeof:xxx' 可用的实体类别匹配器注册表（移植自 ServerCore）。
 */
public final class EntityTypeTests {

    private static final Map<String, EntityTypeTest<? super Entity, ?>> ENTITY_TYPE_TESTS = new HashMap<>();

    public static final EntityTypeTest<? super Entity, ?> MOB = register("mob", Mob.class);
    public static final EntityTypeTest<? super Entity, ?> MONSTER = register("monster", Enemy.class);
    public static final EntityTypeTest<? super Entity, ?> RAIDER = register("raider", Raider.class);
    public static final EntityTypeTest<? super Entity, ?> AMBIENT = register("ambient", AmbientCreature.class);
    public static final EntityTypeTest<? super Entity, ?> ANIMAL = register("animal", AgeableMob.class);
    public static final EntityTypeTest<? super Entity, ?> NEUTRAL = register("neutral", NeutralMob.class);
    public static final EntityTypeTest<? super Entity, ?> WATER_ANIMAL = register("water_animal", WaterAnimal.class);
    public static final EntityTypeTest<? super Entity, ?> FLYING_ANIMAL = register("flying_animal", FlyingAnimal.class);
    public static final EntityTypeTest<? super Entity, ?> FLYING_MONSTER = register("flying_monster", FlyingMob.class);
    public static final EntityTypeTest<? super Entity, ?> VILLAGER = register("villager", Npc.class);
    public static final EntityTypeTest<? super Entity, ?> PROJECTILE = register("projectile", Projectile.class);

    public static EntityTypeTest<? super Entity, ?> register(String key, Class<?> clazz) {
        EntityTypeTest<? super Entity, ?> matcher = EntityTypeTest.forClass(clazz);
        ENTITY_TYPE_TESTS.put(key, matcher);
        return matcher;
    }

    public static EntityTypeTest<? super Entity, ?> get(String key) {
        return ENTITY_TYPE_TESTS.get(key);
    }

    private EntityTypeTests() {
    }
}
