/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.menubroadcast;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.menubroadcast.MenuBroadcastStats;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Container-menu broadcast all-equal precheck (audit doc §阶段5·5.6): skip the whole vanilla
 * {@code broadcastChanges} loop when nothing changed since the last broadcast.
 *
 * <p>Vanilla 1.21.1 {@code broadcastChanges} walks <em>every</em> slot once per tick per open
 * menu — {@code Slot.getItem} + {@code Objects.requireNonNull} + one {@code Suppliers.memoize}
 * lambda allocation per slot, then {@code triggerSlotListeners} / {@code synchronizeSlotToRemote},
 * both of which internally diff against {@code lastSlots} and do nothing when unchanged. The
 * per-slot lambda allocation happens even when the menu has been completely static for hours;
 * AE2-style large menus pay it per hundred slots per tick.
 *
 * <p>The precheck reproduces the vanilla diff conditions exactly — same {@code lastSlots}
 * comparison as {@code triggerSlotListeners}/{@code synchronizeSlotToRemote}, same
 * {@code remoteCarried} comparison as {@code synchronizeCarriedToRemote}, and a value snapshot
 * cache that mirrors {@code DataSlot.checkAndClearUpdateFlag}'s {@code prevValue} semantics
 * without its clear side effect. When every check is equal the vanilla loop is a no-op anyway,
 * so cancelling it is semantics-preserving: <b>mods that write containers directly</b> (e.g.
 * {@code Container.setItem} bypassing the menu) are caught by the exact same diff the vanilla
 * loop would perform — the precheck is a cheap equivalent of the full diff, not a dirty-slot
 * tracker, so nothing can be missed.
 *
 * <p>Compatibility:
 * <ul>
 *   <li><b>Identical results</b>: skip only when every vanilla condition reports "unchanged",
 *       i.e. exactly when the vanilla loop would have done nothing.</li>
 *   <li><b>No stale state</b>: the data-slot snapshot is refreshed whenever the vanilla path
 *       runs (RETURN), and lazily resized; {@code suppressRemoteUpdates} (client bulk-update
 *       window) defers to vanilla entirely.</li>
 *   <li><b>Config-gated</b>: {@code menu-broadcast.enabled} (default off — P3 "measure first",
 *       enable after a production profile shows broadcast cost), telemetry via
 *       {@code [menu-broadcast]}.</li>
 * </ul>
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin_MenuBroadcast {

    @Shadow
    @Final
    private NonNullList<ItemStack> lastSlots;

    @Shadow
    @Final
    private NonNullList<Slot> slots;

    @Shadow
    @Final
    private List<DataSlot> dataSlots;

    @Shadow
    private ItemStack carried;

    @Shadow
    private ItemStack remoteCarried;

    @Shadow
    private boolean suppressRemoteUpdates;

    /** Snapshot of the last broadcast {@code dataSlot.get()} values (mirrors {@code prevValue}). */
    @Unique
    private int[] prts$dataValues;

    /**
     * Cooldown (in ticks) after a precheck failure during which {@code broadcastChanges} runs
     * the vanilla path without scanning. A dense-mutation menu (something changes every tick)
     * would otherwise pay precheck-full-scan + vanilla-full-scan every tick (2x traversal);
     * the cooldown turns that into plain vanilla runs, and a menu that has gone static again
     * re-arms the precheck once the cooldown expires (at most {@value COOLDOWN_TICKS} ticks of
     * plain-vanilla cost after the change stops). Semantics unchanged: the vanilla path itself
     * performs the identical diff.
     */
    @Unique
    private static final int COOLDOWN_TICKS = 20;

    @Unique
    private int prts$precheckCooldown;

    /** True when the precheck short-circuited the current broadcast (RETURN skips cache sync). */
    @Unique
    private boolean prts$skippedThisTick;

    @Inject(method = "broadcastChanges", at = @At("HEAD"), cancellable = true)
    private void prts$precheck(CallbackInfo ci) {
        if (!PRTSFeaturesConfig.menuBroadcastEnabled) {
            return;
        }
        if (this.suppressRemoteUpdates) {
            // vanilla defers carried sync during bulk remote updates; stay out of that window
            return;
        }
        if (this.prts$precheckCooldown > 0) {
            // recent change: plain vanilla runs until the cooldown expires
            this.prts$precheckCooldown--;
            return;
        }
        // dataSlots first (cheap, few of them): progress-bar style menus (furnace, AE2
        // terminals) mutate data slots every tick — fail here before scanning any item slots
        int[] cache = this.prts$dataValues;
        if (cache == null || cache.length < this.dataSlots.size()) {
            // cache not ready (first broadcast / slots added since): vanilla path refreshes it
            this.prts$failAndCooldown();
            MenuBroadcastStats.increment("fullBroadcasts");
            return;
        }
        for (int i = 0; i < this.dataSlots.size(); i++) {
            if (this.dataSlots.get(i).get() != cache[i]) {
                this.prts$failAndCooldown();
                MenuBroadcastStats.increment("fullBroadcasts");
                return;
            }
        }
        // slots: same lastSlots diff the vanilla loop performs per slot
        int n = this.lastSlots.size();
        for (int i = 0; i < n; i++) {
            if (!ItemStack.matches(this.lastSlots.get(i), this.slots.get(i).getItem())) {
                this.prts$failAndCooldown();
                MenuBroadcastStats.increment("fullBroadcasts");
                return;
            }
        }
        // carried: same remoteCarried diff as synchronizeCarriedToRemote
        if (!ItemStack.matches(this.carried, this.remoteCarried)) {
            this.prts$failAndCooldown();
            MenuBroadcastStats.increment("fullBroadcasts");
            return;
        }
        // all equal -> the vanilla loop would have done nothing; skip it entirely
        this.prts$skippedThisTick = true;
        MenuBroadcastStats.increment("skippedBroadcasts");
        MenuBroadcastStats.addSlots(n);
        ci.cancel();
    }

    @Unique
    private void prts$failAndCooldown() {
        this.prts$precheckCooldown = COOLDOWN_TICKS;
    }

    @Inject(method = "broadcastChanges", at = @At("RETURN"))
    private void prts$syncDataCache(CallbackInfo ci) {
        if (!PRTSFeaturesConfig.menuBroadcastEnabled) {
            return;
        }
        if (this.prts$skippedThisTick) {
            // precheck short-circuited: nothing changed, cache is already current
            this.prts$skippedThisTick = false;
            return;
        }
        // refresh the value snapshot after the vanilla path ran (it updated its own prevValues)
        int size = this.dataSlots.size();
        int[] cache = this.prts$dataValues;
        if (cache == null || cache.length < size) {
            cache = new int[Math.max(size, 8)];
            this.prts$dataValues = cache;
        }
        for (int i = 0; i < size; i++) {
            cache[i] = this.dataSlots.get(i).get();
        }
    }
}
