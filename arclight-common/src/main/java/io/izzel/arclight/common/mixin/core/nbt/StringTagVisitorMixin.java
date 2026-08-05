package io.izzel.arclight.common.mixin.core.nbt;

import net.minecraft.nbt.StringTagVisitor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 防御性修复：NBT 序列化时若存在 null key（如被 carryon 等模组扛起的方块 NBT 被某模组写入 null key），
 * StringTagVisitor.visitCompound 对 key 列表排序会抛 NPE（ComparableTimSort "pivot is null"）。
 * 排序前过滤 null key，任何来源的污染数据都不再导致崩溃。
 */
@Mixin(StringTagVisitor.class)
public class StringTagVisitorMixin {

    @Redirect(method = "visitCompound",
            at = @At(value = "INVOKE", target = "Ljava/util/Collections;sort(Ljava/util/List;)V"))
    private void arclight$sortKeys(List<String> list) {
        list.removeIf(Objects::isNull);
        Collections.sort(list);
    }
}
