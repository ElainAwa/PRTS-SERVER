package io.izzel.arclight.common.mixin.bukkit;

import io.izzel.arclight.common.bridge.bukkit.ItemMetaBridge;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import org.bukkit.craftbukkit.v.inventory.CraftMetaBlockState;
import org.bukkit.craftbukkit.v.inventory.CraftMetaItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

@Mixin(value = CraftMetaItem.class, remap = false)
public abstract class CraftMetaItemMixin implements ItemMetaBridge {

    /**
     * 基类 CraftMetaItem 没有 minecraft:block_entity_data 的字段，但该组件被
     * CraftMetaBlockState.BLOCK_ENTITY_TAG.TYPE 声明进全局 HANDLED_TAGS ——
     * <init>(DataComponentPatch) 的遍历循环因此跳过它：既不进 unhandledTags
     * 也不存储，导致 mod 方块物品（如 SBW 集装箱）经 CraftItemStack
     * asBukkitCopy/asNMSCopy 往返（创造模式槽位同步、插件 ItemStack
     * 序列化等）后整个组件静默丢失。
     * 对非 CraftMetaBlockState 的实例放行到 unhandledTags 分支，原样保留。
     */
    @Redirect(method = "<init>(Lnet/minecraft/core/component/DataComponentPatch;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;contains(Ljava/lang/Object;)Z"))
    private boolean arclight$keepUnhandledBlockEntityData(Set<DataComponentType> handled, Object componentType) {
        if (componentType == DataComponents.BLOCK_ENTITY_DATA && !(((Object) this) instanceof CraftMetaBlockState)) {
            return false;
        }
        return handled.contains(componentType);
    }
}
