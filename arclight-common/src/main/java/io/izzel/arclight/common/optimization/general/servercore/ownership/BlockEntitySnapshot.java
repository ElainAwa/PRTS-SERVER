package io.izzel.arclight.common.optimization.general.servercore.ownership;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Immutable value snapshot of a container block entity: item copies plus
 * the type key and position, with an open extras slot for future typed fields.
 */
public record BlockEntitySnapshot(String typeKey, List<ItemStack> items, Map<String, Object> extras, BlockPos pos) {
}
