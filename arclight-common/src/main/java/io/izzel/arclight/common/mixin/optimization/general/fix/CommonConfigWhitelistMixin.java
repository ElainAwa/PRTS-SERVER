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

/** 守卫 RevelationFix CommonConfig.inWhitelist null 字段 NPE（"崩溃 A"）。HEAD 注入 null 守卫，缺失时静默返回 false。 */

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
