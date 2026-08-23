/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.zanghero.unlockablerecipes.UnlockableRecipes;
import com.zanghero.unlockablerecipes.network.RecipeBookPayload;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * unlockable_recipes 在玩家登录时把 {@link ClientboundUpdateRecipesPacket} 广播给
 * 全体在线玩家：每个客户端收到后触发 NeoForge RecipesUpdatedEvent，JEI 据此全量
 * 停止/重建（本整合包实测 1-4.6 秒，跑在 Render thread），表现为"有人进服时所有
 * 其他人客户端未响应"。
 *
 * <p>登录场景只需要同步进服者本人（vanilla 登录流程本来就给进服者发配方包，该 mod
 * 还需补发自己的锁定状态 payload）。锁/解锁配方的全服同步路径保持不变——那种场景
 * 确实需要所有人刷新。这里 HEAD 取消原广播并等量发给进服者。</p>
 */
@LoadIfMod(modid = "unlockable_recipes", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = UnlockableRecipes.class, remap = false)
public abstract class UnlockableRecipesMixin_LoginSync {

    @Inject(method = "onPlayerLogin", at = @At("HEAD"), cancellable = true, remap = false)
    private void arclight$syncRecipeStateOnlyToJoiner(PlayerEvent.PlayerLoggedInEvent event, CallbackInfo ci) {
        Player player = event.getEntity();
        MinecraftServer server = player.getServer();
        if (server == null || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        // 等价原 mod 的 syncRecipeState(server)：配方包 + 锁定状态 payload，只发进服者。
        serverPlayer.connection.send(new ClientboundUpdateRecipesPacket(server.getRecipeManager().getRecipes()));
        PacketDistributor.sendToPlayer(serverPlayer, RecipeBookPayload.forServer(server, false, false));
        ci.cancel();
    }
}
