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
