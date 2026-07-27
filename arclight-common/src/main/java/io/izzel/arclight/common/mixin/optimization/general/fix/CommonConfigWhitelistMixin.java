package io.izzel.arclight.common.mixin.optimization.general.fix;

import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * [PRTS 本服维护者 2026-07-23]
 * 修复 RevelationFix（revelationfix@4.0，内嵌于 GoetyRevelation-2.3.1.jar 的 jarjar 嵌套）
 * 在本服 / Arclight 混合服务端下造成的【inWhitelist NPE 崩溃】（即"崩溃 A"）。
 *
 * 根因（崩溃报告 crash-2026-07-23_03.14.41 实锤）：
 *   CommonConfig.whitelistEntities / whitelistItems（均为 public static Set）在配置尚未灌入时仍为 null，
 *   Goety Apostle.netherUpgrade 调用 inWhitelist(...) -> *.contains(...) -> NPE 崩服。
 *
 * 方案（最小侵入 NPE 保护）：
 *   两个 inWhitelist 重载的 HEAD 各注入守卫——若对应白名单字段为 null，
 *   视为"未初始化 / 不在白名单"，直接返回 false 并取消原方法体，彻底避免 NPE；
 *   仅首次触发打一条 WARN 便于排查配置为何没加载。正常情况字段已初始化，逻辑零改动。
 *
 * @Pseudo：RevelationFix 内嵌于 GoetyRevelation，缺失时本 mixin 静默跳过，绝不拖慢/破坏启动。
 */
@Pseudo
@Mixin(targets = "com.mega.revelationfix.common.config.CommonConfig", remap = false)
public class CommonConfigWhitelistMixin {

    @Shadow
    private static Set<EntityType<?>> whitelistEntities;
    @Shadow
    private static Set<Item> whitelistItems;

    private static final Logger LOGGER = LogManager.getLogger("PRTS-RevelationFix-CFG");
    private static boolean luminara$entitiesWarned = false;
    private static boolean luminara$itemsWarned = false;

    @Inject(
        method = "inWhitelist(Lnet/minecraft/world/entity/Entity;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void luminara$guardEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (whitelistEntities == null) {
            if (!luminara$entitiesWarned) {
                luminara$entitiesWarned = true;
                LOGGER.warn("[PRTS-RevelationFix] CommonConfig.whitelistEntities 未初始化(null)，inWhitelist 已安全返回 false 以避免 NPE 崩服");
            }
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Inject(
        method = "inWhitelist(Lnet/minecraft/world/item/Item;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void luminara$guardItem(Item item, CallbackInfoReturnable<Boolean> cir) {
        if (whitelistItems == null) {
            if (!luminara$itemsWarned) {
                luminara$itemsWarned = true;
                LOGGER.warn("[PRTS-RevelationFix] CommonConfig.whitelistItems 未初始化(null)，inWhitelist 已安全返回 false 以避免 NPE 崩服");
            }
            cir.setReturnValue(false);
            cir.cancel();
        }
    }
}
