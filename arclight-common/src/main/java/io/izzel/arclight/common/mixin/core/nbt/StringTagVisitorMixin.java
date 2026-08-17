package io.izzel.arclight.common.mixin.core.nbt;

import com.google.common.collect.Lists;
import net.minecraft.nbt.StringTagVisitor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;

/**
 * 防御性修复：NBT 序列化时若存在 null key（如被 carryon 等模组扛起的方块 NBT 被某模组写入 null key），
 * StringTagVisitor.visitCompound 对 key 列表排序会抛 NPE（ComparableTimSort "pivot is null"）。
 * 排序前过滤 null key，任何来源的污染数据都不再导致崩溃。
 *
 * <p>2026-08-16 生产两次崩溃（crash-2026-08-16_21.27.21 / 21.30.04）：CarryOn 的
 * ServerTick 回调在主线程对同一份被并发修改的 CompoundTag 调 toString，HashMap 快照在
 * keysToArray 抛 ArrayIndexOutOfBoundsException。这里对 key 快照做有限重试，任何
 * 模组并发修改 tag 都不会把服务器打崩；重试失败则保留原异常语义。</p>
 */
@Mixin(StringTagVisitor.class)
public class StringTagVisitorMixin {

    @Redirect(method = "visitCompound",
            at = @At(value = "INVOKE",
                    target = "Lcom/google/common/collect/Lists;newArrayList(Ljava/lang/Iterable;)Ljava/util/ArrayList;"))
    private static ArrayList<Object> arclight$snapshotKeys(Iterable<?> iterable) {
        for (int attempt = 0; attempt < 16; attempt++) {
            try {
                return Lists.newArrayList(iterable);
            } catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException e) {
                Thread.onSpinWait();
            }
        }
        throw new ConcurrentModificationException("NBT tag modified concurrently during StringTagVisitor snapshot");
    }

    @Redirect(method = "visitCompound",
            at = @At(value = "INVOKE", target = "Ljava/util/Collections;sort(Ljava/util/List;)V"))
    private static void arclight$sortKeys(List<String> list) {
        list.removeIf(Objects::isNull);
        Collections.sort(list);
    }
}
