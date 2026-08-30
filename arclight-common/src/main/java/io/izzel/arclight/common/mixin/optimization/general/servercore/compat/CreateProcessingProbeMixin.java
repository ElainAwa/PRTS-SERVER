/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.BeltDeployerCallbacks;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.fluids.spout.FillingBySpout;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * [探针-临时] create 装配/注液并行诊断：deployer 装配与 spout 注液在 region worker 上
 * 失败的定位（打线程 + 状态 + 结果）。定位完成后删除。
 */
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
public abstract class CreateProcessingProbeMixin {

    @Mixin(value = BeltDeployerCallbacks.class, remap = false)
    public abstract static class DeployerProbe {

        @Inject(method = "whenItemHeld", at = @At("RETURN"))
        private static void prts$probeHeld(TransportedItemStack stack,
                                           TransportedItemStackHandlerBehaviour handler,
                                           DeployerBlockEntity deployer,
                                           CallbackInfoReturnable<BeltProcessingBehaviour.ProcessingResult> cir) {
            String st = "?";
            int timer = -1;
            boolean rl = false;
            boolean hasPlayer = deployer.getPlayer() != null;
            try {
                var stateF = DeployerBlockEntity.class.getDeclaredField("state");
                stateF.setAccessible(true);
                st = String.valueOf(stateF.get(deployer));
                var timerF = DeployerBlockEntity.class.getDeclaredField("timer");
                timerF.setAccessible(true);
                timer = timerF.getInt(deployer);
                var rlF = DeployerBlockEntity.class.getDeclaredField("redstoneLocked");
                rlF.setAccessible(true);
                rl = rlF.getBoolean(deployer);
            } catch (Throwable ignored) {
            }
            LogManager.getLogger("PRTS-CreateProbe").info("deployer held t={} pos={} result={} state={} timer={} rl={} player={} speed={}",
                    Thread.currentThread().getName(), deployer.getBlockPos(), cir.getReturnValue(),
                    st, timer, rl, hasPlayer, deployer.getSpeed());
        }

        @Inject(method = "activate", at = @At("HEAD"))
        private static void prts$probeActivate(TransportedItemStack stack,
                                               TransportedItemStackHandlerBehaviour handler,
                                               DeployerBlockEntity deployer,
                                               Recipe<?> recipe,
                                               CallbackInfo ci) {
            LogManager.getLogger("PRTS-CreateProbe").info("deployer activate t={} pos={} recipe={}",
                    Thread.currentThread().getName(), deployer.getBlockPos(), recipe);
        }

        @Redirect(method = "activate",
                at = @At(value = "INVOKE",
                        target = "Lcom/simibubi/create/foundation/recipe/RecipeApplier;applyRecipeOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/Recipe;Z)Ljava/util/List;"))
        private static java.util.List<net.minecraft.world.item.ItemStack> prts$probeResult(
                Level level, net.minecraft.world.item.ItemStack stack, Recipe recipe, boolean b) {
            java.util.List<net.minecraft.world.item.ItemStack> result =
                    com.simibubi.create.foundation.recipe.RecipeApplier.applyRecipeOn(level, stack, recipe, b);
            LogManager.getLogger("PRTS-CreateProbe").info("deployer result t={} in={} resultSize={} first={}",
                    Thread.currentThread().getName(), stack, result.size(),
                    result.isEmpty() ? "EMPTY" : result.get(0));
            return result;
        }
    }

    @Mixin(value = FillingBySpout.class, remap = false)
    public abstract static class SpoutProbe {

        @Inject(method = "fillItem", at = @At("HEAD"))
        private static void prts$probeFillHead(Level level, int amount, ItemStack stack, FluidStack fluid,
                                               CallbackInfoReturnable<ItemStack> cir) {
            LogManager.getLogger("PRTS-CreateProbe").info("spout fill t={} in={} nbt={} fluid={}/{} amount={}",
                    Thread.currentThread().getName(), stack, prts$stackNbt(stack, level), fluid, fluid.getAmount(), amount);
        }

        @Inject(method = "fillItem", at = @At("RETURN"))
        private static void prts$probeFill(Level level, int amount, ItemStack stack, FluidStack fluid,
                                           CallbackInfoReturnable<ItemStack> cir) {
            LogManager.getLogger("PRTS-CreateProbe").info("spout fill t={} in={} -> out={} outNbt={}",
                    Thread.currentThread().getName(), stack, cir.getReturnValue(),
                    prts$stackNbt(cir.getReturnValue(), level));
        }

        private static String prts$stackNbt(ItemStack stack, Level level) {
            if (stack == null || stack.isEmpty()) {
                return "empty";
            }
            try {
                net.minecraft.nbt.Tag tag = stack.save(level.registryAccess());
                String s = String.valueOf(tag);
                return s.length() > 260 ? s.substring(0, 260) + "..." : s;
            } catch (Throwable t) {
                return "ERR:" + t.getClass().getSimpleName();
            }
        }
    }

    /** 便携存储接口（移动接口）探针：只打目标装置附近，限流 2s。 */
    @Mixin(value = com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity.class, remap = false)
    public abstract static class PsiProbe {

        private static long prts$lastPsiLog = 0L;

        @Inject(method = "tick", at = @At("HEAD"))
        private void prts$probePsiTick(CallbackInfo ci) {
            com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity self =
                    (com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity) (Object) this;
            net.minecraft.core.BlockPos pos = self.getBlockPos();
            if (Math.abs(pos.getX() - 235) > 8 || Math.abs(pos.getY() - 113) > 8 || Math.abs(pos.getZ() + 35) > 8) {
                return; // 只打用户装置附近
            }
            long now = System.nanoTime();
            if (now - prts$lastPsiLog < 2_000_000_000L) {
                return; // 限流 2s
            }
            prts$lastPsiLog = now;
            java.lang.reflect.Field f;
            int transferTimer = -1;
            boolean powered = false;
            boolean keepAlive = false;
            String connected = "null";
            try {
                f = com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity.class.getDeclaredField("transferTimer");
                f.setAccessible(true);
                transferTimer = f.getInt(self);
                f = com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity.class.getDeclaredField("powered");
                f.setAccessible(true);
                powered = f.getBoolean(self);
                f = com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity.class.getDeclaredField("keepAlive");
                f.setAccessible(true);
                keepAlive = f.getInt(self) > 0;
                f = com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity.class.getDeclaredField("connectedEntity");
                f.setAccessible(true);
                Object ce = f.get(self);
                connected = ce == null ? "null" : ce.getClass().getSimpleName() + "/alive=" + (ce instanceof net.minecraft.world.entity.Entity e ? e.isAlive() : "?");
            } catch (Throwable ignored) {
            }
            // capability 槽位内容（contraption 存储绑定）：前 3 槽
            String slots = "?";
            try {
                if (self instanceof com.simibubi.create.content.contraptions.actors.psi.PortableItemInterfaceBlockEntity itemBe) {
                    java.lang.reflect.Field capF = com.simibubi.create.content.contraptions.actors.psi.PortableItemInterfaceBlockEntity.class.getDeclaredField("capability");
                    capF.setAccessible(true);
                    Object cap = capF.get(itemBe);
                    if (cap != null) {
                        java.lang.reflect.Method gm = cap.getClass().getMethod("getSlots");
                        int n = Math.min((int) gm.invoke(cap), 3);
                        java.lang.reflect.Method gs = cap.getClass().getMethod("getStackInSlot", int.class);
                        StringBuilder sb = new StringBuilder("slots=" + n);
                        for (int i = 0; i < n; i++) {
                            sb.append(" [").append(i).append("]=").append(gs.invoke(cap, i));
                        }
                        slots = sb.toString();
                    } else {
                        slots = "cap=null";
                    }
                } else {
                    slots = "fluid-psi";
                }
            } catch (Throwable ignored) {
            }
            // 模拟漏斗查询：getCapability(ITEM, DOWN) 是否暴露（全反射，无编译依赖）
            String capQuery = "?";
            try {
                Object levelObj = self.getLevel();
                if (levelObj != null) {
                    Class<?> blockCapCls = Class.forName("net.neoforged.neoforge.capabilities.BlockCapability");
                    Object itemCap = Class.forName("net.neoforged.neoforge.capabilities.Capabilities$ItemHandler")
                            .getField("BLOCK").get(null);
                    java.lang.reflect.Method gcm = levelObj.getClass()
                            .getMethod("getCapability", blockCapCls, net.minecraft.core.BlockPos.class, Object.class);
                    Object found = gcm.invoke(levelObj, itemCap, pos, net.minecraft.core.Direction.DOWN);
                    if (found == null) {
                        capQuery = "NULL";
                    } else {
                        Object nObj = found.getClass().getMethod("getSlots").invoke(found);
                        int n = nObj instanceof Integer i ? i : -1;
                        java.lang.reflect.Method gs = found.getClass().getMethod("getStackInSlot", int.class);
                        StringBuilder sb = new StringBuilder(found.getClass().getSimpleName() + " slots=" + n);
                        for (int i = 0; i < Math.min(n, 2); i++) {
                            sb.append(" [").append(i).append("]=").append(gs.invoke(found, i));
                        }
                        capQuery = sb.toString();
                    }
                }
            } catch (Throwable ignored) {
            }
            LogManager.getLogger("PRTS-CreateProbe").info("psi tick t={} pos={} timer={} powered={} keepAlive={} connected={} canTransfer={} transferring={} slots={} capQuery={}",
                    Thread.currentThread().getName(), pos, transferTimer, powered, keepAlive, connected,
                    self.canTransfer(), self.isTransferring(), slots, capQuery);
            // 模拟提取器 extractItem(0, 1, simulate=true)：直接查询路径
            String tryExtract = "?";
            String cachePath = "?";
            try {
                Object levelObj = self.getLevel();
                if (levelObj != null) {
                    Class<?> blockCapCls = Class.forName("net.neoforged.neoforge.capabilities.BlockCapability");
                    Object itemCap = Class.forName("net.neoforged.neoforge.capabilities.Capabilities$ItemHandler")
                            .getField("BLOCK").get(null);
                    java.lang.reflect.Method gcm = levelObj.getClass()
                            .getMethod("getCapability", blockCapCls, net.minecraft.core.BlockPos.class, Object.class);
                    Object found = gcm.invoke(levelObj, itemCap, pos, net.minecraft.core.Direction.DOWN);
                    if (found != null) {
                        java.lang.reflect.Method ex = found.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
                        Object out = ex.invoke(found, 0, 1, true);
                        tryExtract = "sim=" + out;
                    } else {
                        tryExtract = "NULL";
                    }
                    // BlockCapabilityCache 路径（提取器实际用的）：create + getCapability
                    try {
                        Class<?> cacheCls = Class.forName("net.neoforged.neoforge.capabilities.BlockCapabilityCache");
                        java.lang.reflect.Method createM = cacheCls.getMethod("create", blockCapCls,
                                net.minecraft.server.level.ServerLevel.class, net.minecraft.core.BlockPos.class, Object.class);
                        Object cache = createM.invoke(null, itemCap, levelObj, pos, net.minecraft.core.Direction.DOWN);
                        java.lang.reflect.Method getM = cacheCls.getMethod("getCapability");
                        Object cached = getM.invoke(cache);
                        cachePath = cached == null ? "NULL" : cached.getClass().getSimpleName();
                        if (cached != null) {
                            java.lang.reflect.Method ex = cached.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
                            cachePath += "/extract0=" + ex.invoke(cached, 0, 1, true);
                        }
                    } catch (Throwable t) {
                        cachePath = "ERR:" + t;
                    }
                }
            } catch (Throwable ignored) {
            }
            LogManager.getLogger("PRTS-CreateProbe").info("psi extract pos={} direct={} cache={}", pos, tryExtract, cachePath);
        }
    }

    /** PSI 对接/断开切换探针：定位 capability 好/空切换源。 */
    @Mixin(value = com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity.class, remap = false)
    public abstract static class PsiSwitchProbe {

        private static long prts$lastSwitchLog = 0L;

        @Inject(method = "startTransferringTo", at = @At("HEAD"))
        private void prts$probeStartTransfer(com.simibubi.create.content.contraptions.Contraption contraption,
                                             float distance, CallbackInfo ci) {
            prts$logSwitch("startTransferringTo", (Object) this, contraption);
        }

        @Inject(method = "stopTransferring", at = @At("HEAD"))
        private void prts$probeStopTransfer(CallbackInfo ci) {
            prts$logSwitch("stopTransferring", (Object) this, null);
        }

        private void prts$logSwitch(String what, Object selfObj, Object contraption) {
            com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity self =
                    (com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity) selfObj;
            net.minecraft.core.BlockPos pos = self.getBlockPos();
            if (Math.abs(pos.getX() - 235) > 16 || Math.abs(pos.getY() - 113) > 16 || Math.abs(pos.getZ() + 35) > 16) {
                return;
            }
            long now = System.nanoTime();
            if (now - prts$lastSwitchLog < 1_000_000_000L) {
                return; // 限流 1s
            }
            prts$lastSwitchLog = now;
            String ce = "null";
            try {
                java.lang.reflect.Field f = com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity.class
                        .getDeclaredField("connectedEntity");
                f.setAccessible(true);
                Object e = f.get(self);
                ce = e == null ? "null" : e.getClass().getSimpleName() + "/alive="
                        + (e instanceof net.minecraft.world.entity.Entity ee ? ee.isAlive() : "?");
            } catch (Throwable ignored) {
            }
            String cap = "?";
            try {
                if (selfObj instanceof com.simibubi.create.content.contraptions.actors.psi.PortableItemInterfaceBlockEntity itemBe) {
                    java.lang.reflect.Field cf = com.simibubi.create.content.contraptions.actors.psi.PortableItemInterfaceBlockEntity.class
                            .getDeclaredField("capability");
                    cf.setAccessible(true);
                    Object c = cf.get(itemBe);
                    cap = c == null ? "null" : c.getClass().getSimpleName() + "/slots="
                            + c.getClass().getMethod("getSlots").invoke(c);
                }
            } catch (Throwable ignored) {
            }
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            String caller = st.length > 3 ? st[3].getClassName() + "." + st[3].getMethodName() : "?";
            // 深栈：找 startTransferringTo 的触发源（contraption 检测 / notifyContraptions / tick）
            StringBuilder deep = new StringBuilder();
            for (int i = 2; i < Math.min(st.length, 9); i++) {
                deep.append(" < ").append(st[i].getClassName().substring(st[i].getClassName().lastIndexOf('.') + 1))
                        .append(".").append(st[i].getMethodName());
            }
            // start 瞬间顺带查溜槽 grabCapability（看对接窗口内溜槽能否拿到好 handler）
            String chuteView = "";
            if ("startTransferringTo".equals(what)) {
                try {
                    net.minecraft.world.level.block.entity.BlockEntity below = self.getLevel() != null
                            ? self.getLevel().getBlockEntity(pos.below()) : null;
                    if (below instanceof com.simibubi.create.content.logistics.chute.ChuteBlockEntity chute) {
                        java.lang.reflect.Method grabM = com.simibubi.create.content.logistics.chute.ChuteBlockEntity.class
                                .getDeclaredMethod("grabCapability", net.minecraft.core.Direction.class);
                        grabM.setAccessible(true);
                        Object h = grabM.invoke(chute, net.minecraft.core.Direction.UP);
                        chuteView = " chuteGrab=" + (h == null ? "null" : h.getClass().getSimpleName() + "/slots="
                                + h.getClass().getMethod("getSlots").invoke(h));
                    }
                } catch (Throwable ignored) {
                }
            }
            LogManager.getLogger("PRTS-CreateProbe").info("psi switch t={} {} @ {} conn={} cap={} caller={}{}{}",
                    Thread.currentThread().getName(), what, pos, ce, cap, caller, deep, chuteView);
        }
    }

    /** 注液器 tick 探针：定位哪个注液器在工作/卡住（限流 5s，范围 ±32）。 */
    @Mixin(value = com.simibubi.create.content.fluids.spout.SpoutBlockEntity.class, remap = false)
    public abstract static class SpoutTickProbe {

        private static long prts$lastSpoutLog = 0L;

        @Inject(method = "tick", at = @At("HEAD"))
        private void prts$probeSpoutTick(CallbackInfo ci) {
            com.simibubi.create.content.fluids.spout.SpoutBlockEntity self =
                    (com.simibubi.create.content.fluids.spout.SpoutBlockEntity) (Object) this;
            net.minecraft.core.BlockPos pos = self.getBlockPos();
            if (Math.abs(pos.getX() - 470) > 32 || Math.abs(pos.getY() - 66) > 16 || Math.abs(pos.getZ() - 1200) > 32) {
                return;
            }
            long now = System.nanoTime();
            if (now - prts$lastSpoutLog < 5_000_000_000L) {
                return; // 限流 5s
            }
            prts$lastSpoutLog = now;
            String fluid = "?";
            String pticks = "?";
            String below = "?";
            try {
                java.lang.reflect.Method gf = com.simibubi.create.content.fluids.spout.SpoutBlockEntity.class
                        .getDeclaredMethod("getCurrentFluidInTank");
                gf.setAccessible(true);
                fluid = String.valueOf(gf.invoke(self));
                pticks = String.valueOf(self.processingTicks);
                net.minecraft.world.level.block.entity.BlockEntity be = self.getLevel() != null
                        ? self.getLevel().getBlockEntity(pos.below(2)) : null;
                below = be == null ? "null" : be.getClass().getSimpleName();
            } catch (Throwable ignored) {
            }
            LogManager.getLogger("PRTS-CreateProbe").info("spout tick t={} pos={} fluid={} pticks={} below2={}",
                    Thread.currentThread().getName(), pos, fluid, pticks, below);
        }
    }

    /** 漏斗探针：目标装置附近 ±16，限流 2s。 */
    @Mixin(value = net.minecraft.world.level.block.entity.HopperBlockEntity.class, remap = false)
    public abstract static class HopperProbe {

        private static long prts$lastHopperLog = 0L;

        @Inject(method = "tick", at = @At("HEAD"))
        private static void prts$probeHopperTick(net.minecraft.world.level.Level level,
                                                 net.minecraft.core.BlockPos pos,
                                                 net.minecraft.world.level.block.state.BlockState state,
                                                 net.minecraft.world.level.block.entity.HopperBlockEntity self,
                                                 CallbackInfo ci) {
            if (Math.abs(pos.getX() - 235) > 16 || Math.abs(pos.getY() - 113) > 16 || Math.abs(pos.getZ() + 35) > 16) {
                return;
            }
            long now = System.nanoTime();
            if (now - prts$lastHopperLog < 2_000_000_000L) {
                return;
            }
            prts$lastHopperLog = now;
            String source = "null";
            String cap = "?";
            String sim = "?";
            try {
                java.lang.reflect.Method srcM = net.minecraft.world.level.block.entity.HopperBlockEntity.class
                        .getMethod("getSourceBlockEntity");
                Object src = srcM.invoke(self);
                if (src != null) {
                    source = src.getClass().getSimpleName() + "@" + ((net.minecraft.world.level.block.entity.BlockEntity) src).getBlockPos();
                }
                if (src instanceof net.minecraft.world.level.block.entity.BlockEntity srcBe && srcBe.getLevel() != null) {
                    Class<?> blockCapCls = Class.forName("net.neoforged.neoforge.capabilities.BlockCapability");
                    Object itemCap = Class.forName("net.neoforged.neoforge.capabilities.Capabilities$ItemHandler")
                            .getField("BLOCK").get(null);
                    java.lang.reflect.Method gcm = srcBe.getLevel().getClass()
                            .getMethod("getCapability", blockCapCls, net.minecraft.core.BlockPos.class, Object.class);
                    Object found = gcm.invoke(srcBe.getLevel(), itemCap, srcBe.getBlockPos(), net.minecraft.core.Direction.DOWN);
                    if (found == null) {
                        cap = "NULL";
                    } else {
                        Object nObj = found.getClass().getMethod("getSlots").invoke(found);
                        cap = found.getClass().getSimpleName() + "/slots=" + nObj;
                        java.lang.reflect.Method ex = found.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
                        Object out = ex.invoke(found, 0, 1, true);
                        sim = "extract0=" + out;
                    }
                }
            } catch (Throwable ignored) {
            }
            String cooldown = "?";
            try {
                java.lang.reflect.Method cdM = net.minecraft.world.level.block.entity.HopperBlockEntity.class
                        .getMethod("getCooldownTime");
                cooldown = String.valueOf(cdM.invoke(self));
            } catch (Throwable ignored) {
            }
            LogManager.getLogger("PRTS-CreateProbe").info("hopper tick t={} pos={} source={} cap={} sim={} cooldown={}",
                    Thread.currentThread().getName(), pos, source, cap, sim, cooldown);
        }
    }

    /** 溜槽提取尝试探针：直接看每次 handleInput 的 handler 状态。 */
    @Mixin(value = com.simibubi.create.content.logistics.chute.ChuteBlockEntity.class, remap = false)
    public abstract static class ChuteInputProbe {

        private static long prts$lastInputLog = 0L;

        @Inject(method = "handleInput", at = @At("HEAD"))
        private void prts$probeHandleInput(net.neoforged.neoforge.items.IItemHandler handler, float speed,
                                           CallbackInfo ci) {
            com.simibubi.create.content.logistics.chute.ChuteBlockEntity self =
                    (com.simibubi.create.content.logistics.chute.ChuteBlockEntity) (Object) this;
            net.minecraft.core.BlockPos pos = self.getBlockPos();
            if (Math.abs(pos.getX() - 235) > 16 || Math.abs(pos.getY() - 113) > 16 || Math.abs(pos.getZ() + 35) > 16) {
                return;
            }
            long now = System.nanoTime();
            if (now - prts$lastInputLog < 2_000_000_000L) {
                return;
            }
            prts$lastInputLog = now;
            String h = handler == null ? "null" : handler.getClass().getSimpleName();
            String slots = "?";
            String s0 = "?";
            String ex0 = "?";
            if (handler != null) {
                try {
                    slots = String.valueOf(handler.getClass().getMethod("getSlots").invoke(handler));
                    s0 = String.valueOf(handler.getClass().getMethod("getStackInSlot", int.class).invoke(handler, 0));
                    ex0 = String.valueOf(handler.getClass().getMethod("extractItem", int.class, int.class, boolean.class)
                            .invoke(handler, 0, 1, true));
                } catch (Throwable t) {
                    Throwable c = t instanceof java.lang.reflect.InvocationTargetException ite
                            && ite.getCause() != null ? ite.getCause() : t;
                    h += " ERR:" + c.getClass().getSimpleName();
                }
            }
            String ver = "?";
            try {
                java.lang.reflect.Field vtF = com.simibubi.create.content.logistics.chute.ChuteBlockEntity.class
                        .getDeclaredField("invVersionTracker");
                vtF.setAccessible(true);
                Object vt = vtF.get(self);
                if (vt != null && handler != null) {
                    java.lang.reflect.Method swM = vt.getClass().getMethod("stillWaiting",
                            net.neoforged.neoforge.items.IItemHandler.class);
                    ver = String.valueOf(swM.invoke(vt, handler));
                }
            } catch (Throwable ignored) {
            }
            String canAct = "?";
            try {
                java.lang.reflect.Method caM = com.simibubi.create.content.logistics.chute.ChuteBlockEntity.class
                        .getDeclaredMethod("canActivate");
                caM.setAccessible(true);
                canAct = String.valueOf(caM.invoke(self));
            } catch (Throwable ignored) {
            }
            LogManager.getLogger("PRTS-CreateProbe").info("chute input t={} pos={} handler={} slots={} [0]={} extract0={} canAct={} verWait={}",
                    Thread.currentThread().getName(), pos, h, slots, s0, ex0, canAct, ver);
        }
    }

    /**
     * 智能溜槽探针：挂在 SmartBlockEntity.tick（createlazytick @Overwrite 了
     * ChuteBlockEntity.tick，@Inject 到 ChuteBlockEntity 会被冲突跳过；
     * createlazytick 的 tick 会 invokespecial SmartBlockEntity.tick，
     * 在此拦截对 SmartChute 生效）。坐标过滤 + 限流 2s。
     */
    @Mixin(value = com.simibubi.create.foundation.blockEntity.SmartBlockEntity.class, remap = false)
    public abstract static class ChuteProbe {

        private static long prts$lastChuteLog = 0L;

        @Inject(method = "tick", at = @At("HEAD"))
        private void prts$probeChuteTick(CallbackInfo ci) {
            com.simibubi.create.foundation.blockEntity.SmartBlockEntity self =
                    (com.simibubi.create.foundation.blockEntity.SmartBlockEntity) (Object) this;
            if (!(self instanceof com.simibubi.create.content.logistics.chute.SmartChuteBlockEntity)) {
                return;
            }
            net.minecraft.core.BlockPos pos = self.getBlockPos();
            if (Math.abs(pos.getX() - 235) > 16 || Math.abs(pos.getY() - 113) > 16 || Math.abs(pos.getZ() + 35) > 16) {
                return; // 只打用户装置附近
            }
            long now = System.nanoTime();
            if (now - prts$lastChuteLog < 2_000_000_000L) {
                return; // 限流 2s
            }
            prts$lastChuteLog = now;
            String filter = "none";
            String activ = "?";
            String extract = "?";
            String extracting = "?";
            String pickUp = "?";
            String item = "empty";
            String lazyTick = "?";
            try {
                com.simibubi.create.content.logistics.chute.SmartChuteBlockEntity smart =
                        (com.simibubi.create.content.logistics.chute.SmartChuteBlockEntity) self;
                java.lang.reflect.Field ff = com.simibubi.create.content.logistics.chute.SmartChuteBlockEntity.class.getDeclaredField("filtering");
                ff.setAccessible(true);
                Object fb = ff.get(smart);
                if (fb != null) {
                    Object held = fb.getClass().getMethod("getFilter").invoke(fb);
                    filter = String.valueOf(held);
                }
                java.lang.reflect.Method m = com.simibubi.create.content.logistics.chute.SmartChuteBlockEntity.class.getDeclaredMethod("canActivate");
                m.setAccessible(true);
                activ = String.valueOf(m.invoke(smart));
                m = com.simibubi.create.content.logistics.chute.SmartChuteBlockEntity.class.getDeclaredMethod("getExtractionAmount");
                m.setAccessible(true);
                extract = String.valueOf(m.invoke(smart));
                m = com.simibubi.create.content.logistics.chute.SmartChuteBlockEntity.class.getDeclaredMethod("isExtracting");
                m.setAccessible(true);
                extracting = String.valueOf(m.invoke(smart));
                m = com.simibubi.create.content.logistics.chute.ChuteBlockEntity.class.getDeclaredMethod("canDirectlyInsert");
                m.setAccessible(true);
                pickUp = String.valueOf(m.invoke(smart));
                // canPickUpItems 字段（ChuteBlockEntity）
                try {
                    java.lang.reflect.Field f2 = com.simibubi.create.content.logistics.chute.ChuteBlockEntity.class.getDeclaredField("canPickUpItems");
                    f2.setAccessible(true);
                    pickUp = pickUp + "/field=" + f2.getBoolean(smart);
                } catch (Throwable ignored) {
                }
                // item 字段（chute 内的物品）
                try {
                    java.lang.reflect.Field f3 = com.simibubi.create.content.logistics.chute.ChuteBlockEntity.class.getDeclaredField("item");
                    f3.setAccessible(true);
                    Object it = f3.get(smart);
                    item = String.valueOf(it);
                } catch (Throwable ignored) {
                }
                // createlazytick 的 chuteTick 计数
                try {
                    java.lang.reflect.Field f4 = Class.forName("net.pinkcats.createlazytick.mixin.OptElement.chute.ChuteLazyTickMixin")
                            .getDeclaredField("createLazyTick$chuteTick");
                    f4.setAccessible(true);
                    lazyTick = String.valueOf(f4.getInt(smart));
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
            // 溜槽自己的 capability 缓存（grabCapability 路径）与版本跟踪器
            String ownCache = "?";
            String versionWait = "?";
            try {
                com.simibubi.create.content.logistics.chute.ChuteBlockEntity chute =
                        (com.simibubi.create.content.logistics.chute.ChuteBlockEntity) self;
                java.lang.reflect.Method grabM = com.simibubi.create.content.logistics.chute.ChuteBlockEntity.class
                        .getDeclaredMethod("grabCapability", net.minecraft.core.Direction.class);
                grabM.setAccessible(true);
                Object upHandler = grabM.invoke(chute, net.minecraft.core.Direction.UP);
                if (upHandler == null) {
                    ownCache = "NULL";
                } else {
                    ownCache = upHandler.getClass().getSimpleName() + " id=" + System.identityHashCode(upHandler);
                    try {
                        java.lang.reflect.Method gs = upHandler.getClass().getMethod("getStackInSlot", int.class);
                        Object s0 = gs.invoke(upHandler, 0);
                        ownCache += " [0]=" + s0;
                        java.lang.reflect.Method ex = upHandler.getClass().getMethod("extractItem", int.class, int.class, boolean.class);
                        ownCache += " extract0=" + ex.invoke(upHandler, 0, 1, true);
                    } catch (Throwable t) {
                        Throwable c = t instanceof java.lang.reflect.InvocationTargetException ite
                                && ite.getCause() != null ? ite.getCause() : t;
                        ownCache += " ERR:" + c.getClass().getName() + ":" + c.getMessage();
                        StackTraceElement[] st = c.getStackTrace();
                        if (st.length > 0) {
                            ownCache += " @ " + st[0];
                        }
                        if (st.length > 1) {
                            ownCache += " | " + st[1];
                        }
                    }
                }
                // 版本跟踪器 stillWaiting
                try {
                    java.lang.reflect.Field vtF = com.simibubi.create.content.logistics.chute.ChuteBlockEntity.class
                            .getDeclaredField("invVersionTracker");
                    vtF.setAccessible(true);
                    Object vt = vtF.get(chute);
                    if (vt != null && upHandler != null) {
                        try {
                            java.lang.reflect.Method swM = vt.getClass().getMethod("stillWaiting",
                                    net.neoforged.neoforge.items.IItemHandler.class);
                            versionWait = String.valueOf(swM.invoke(vt, upHandler));
                        } catch (Throwable t) {
                            versionWait = "ERR:" + t.getClass().getSimpleName() + ":" + t.getMessage();
                        }
                    }
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
            // 同 tick 直接查询对比（不经过 grabCapability 的持久缓存）
            String directQuery = "?";
            try {
                Object levelObj = self.getLevel();
                if (levelObj != null) {
                    Class<?> blockCapCls = Class.forName("net.neoforged.neoforge.capabilities.BlockCapability");
                    Object itemCap = Class.forName("net.neoforged.neoforge.capabilities.Capabilities$ItemHandler")
                            .getField("BLOCK").get(null);
                    java.lang.reflect.Method gcm = levelObj.getClass()
                            .getMethod("getCapability", blockCapCls, net.minecraft.core.BlockPos.class, Object.class);
                    Object found = gcm.invoke(levelObj, itemCap, pos.above(), net.minecraft.core.Direction.DOWN);
                    if (found == null) {
                        directQuery = "NULL";
                    } else {
                        directQuery = found.getClass().getSimpleName() + " id=" + System.identityHashCode(found);
                        Object nObj = found.getClass().getMethod("getSlots").invoke(found);
                        directQuery += " slots=" + nObj;
                    }
                }
            } catch (Throwable ignored) {
            }
            LogManager.getLogger("PRTS-CreateProbe").info("chute tick t={} pos={} canActivate={} extract={} extracting={} pickUp={} item={} lazy={} filter={} ownCache={} verWait={} direct={}",
                    Thread.currentThread().getName(), pos, activ, extract, extracting, pickUp, item, lazyTick, filter, ownCache, versionWait, directQuery);
        }
    }
}
