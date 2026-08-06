/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.misc;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = MapItemSavedData.class, priority = 900)
public class MapItemSavedDataMixin {

    // 展示框内的地图无需遍历玩家背包。
    @Redirect(
            method = "tickCarriedBy",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;contains(Lnet/minecraft/world/item/ItemStack;)Z"
            )
    )
    private boolean arclight$reduceInventoryIteration(Inventory inventory, ItemStack stack) {
        if (!ServerCoreConfig.optimizations().optimizeMapTicking()) {
            return inventory.contains(stack);
        }
        return stack.isFramed() || inventory.contains(stack);
    }

    // 玩家名多为纯文本，跳过完整的组件序列化。
    @Redirect(
            method = "tickCarriedBy",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"
            )
    )
    private String arclight$getPlayerName(Component component) {
        if (ServerCoreConfig.optimizations().optimizeMapTicking()
                && component.getContents() instanceof LiteralContents literal) {
            return literal.text();
        }
        return component.getString();
    }
}
