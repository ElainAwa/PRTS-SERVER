/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * Synthetic container-menu broadcast benchmark for the menu-broadcast precheck
 * (audit doc §阶段5·5.6, P3). Driven by {@code /prtsfeatures menubench}.
 */

package io.izzel.arclight.common.compat.prts.feature;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.command.CommandSender;

/**
 * Measures {@code AbstractContainerMenu.broadcastChanges} cost with the menu-broadcast
 * precheck on vs off. Runs on the server thread (commands execute there), constructs a
 * synthetic 64-slot menu (no synchronizer, no listeners), and times a fixed number of
 * broadcast iterations in three mutation patterns:
 * <ul>
 *   <li>{@code static}: nothing changes — the dominant real-world case for an open menu
 *       (player idle), and the case the precheck short-circuits;</li>
 *   <li>{@code sparse}: one slot flips every 100 broadcasts — a player clicking occasionally;</li>
 *   <li>{@code denseFirst}: slot 0 flips every broadcast — precheck fails on its first
 *       comparison, so the cost approaches vanilla (best dense case);</li>
 *   <li>{@code denseLast}: slot 63 flips every broadcast — precheck scans all 64 slots
 *       before failing and handing over to vanilla (worst dense case).</li>
 * </ul>
 * Uses {@code System.nanoTime} around each loop; the precheck is flipped on/off via
 * {@code PRTSFeaturesConfig.menuBroadcastEnabled} and restored afterwards.
 *
 * <p>Why synthetic: {@code broadcastChanges} only runs for menus opened by real players,
 * which a headless test server does not have. The synthetic menu reproduces the exact
 * hot path — per-slot {@code getItem} + memoize lambda allocation + {@code lastSlots}
 * diff — without network or listener overhead, so the numbers are the pure CPU delta
 * of the optimization.
 */
public final class MenuBenchmark {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Optimization");

    private static final int SLOTS = 64;
    private static final int ITERATIONS = 200_000;
    private static final int WARMUP = 2_000;
    private static final int SPARSE_EVERY = 100;

    private static final ItemStack MUTATION = new ItemStack(Items.DIRT, 1);

    private MenuBenchmark() {
    }

    public static void run(CommandSender sender) {
        boolean wasEnabled = PRTSFeaturesConfig.menuBroadcastEnabled;
        try {
            // interleaved A/B (off,on) x2 so JIT warm-up order does not skew the comparison;
            // the summary below uses the second pair (both legs fully warmed)
            PRTSFeaturesConfig.menuBroadcastEnabled = false;
            long staticOff1 = measure(Mode.STATIC);
            long sparseOff1 = measure(Mode.SPARSE);
            long denseFirstOff1 = measure(Mode.DENSE_FIRST);
            long denseLastOff1 = measure(Mode.DENSE_LAST);
            PRTSFeaturesConfig.menuBroadcastEnabled = true;
            long staticOn1 = measure(Mode.STATIC);
            long sparseOn1 = measure(Mode.SPARSE);
            long denseFirstOn1 = measure(Mode.DENSE_FIRST);
            long denseLastOn1 = measure(Mode.DENSE_LAST);
            PRTSFeaturesConfig.menuBroadcastEnabled = false;
            long staticOff2 = measure(Mode.STATIC);
            long sparseOff2 = measure(Mode.SPARSE);
            long denseFirstOff2 = measure(Mode.DENSE_FIRST);
            long denseLastOff2 = measure(Mode.DENSE_LAST);
            PRTSFeaturesConfig.menuBroadcastEnabled = true;
            long staticOn2 = measure(Mode.STATIC);
            long sparseOn2 = measure(Mode.SPARSE);
            long denseFirstOn2 = measure(Mode.DENSE_FIRST);
            long denseLastOn2 = measure(Mode.DENSE_LAST);
            String line = String.format(
                    "[menu-bench] 64-slot x%d broadcasts (2nd warmed pair): static off=%dms on=%dms (%+.1f%%) | "
                            + "sparse off=%dms on=%dms (%+.1f%%) | denseFirst off=%dms on=%dms (%+.1f%%) | "
                            + "denseLast off=%dms on=%dms (%+.1f%%)",
                    ITERATIONS,
                    staticOff2, staticOn2, delta(staticOff2, staticOn2),
                    sparseOff2, sparseOn2, delta(sparseOff2, sparseOn2),
                    denseFirstOff2, denseFirstOn2, delta(denseFirstOff2, denseFirstOn2),
                    denseLastOff2, denseLastOn2, delta(denseLastOff2, denseLastOn2));
            send(sender, line);
        } finally {
            PRTSFeaturesConfig.menuBroadcastEnabled = wasEnabled;
        }
    }

    /** Positive = faster with the precheck on. */
    private static double delta(long off, long on) {
        if (off <= 0) {
            return 0.0;
        }
        return (1.0 - (double) on / off) * 100.0;
    }

    private static long measure(Mode mode) {
        AbstractContainerMenu menu = new BenchMenu(new SimpleContainer(SLOTS));
        menu.broadcastChanges(); // prime lastSlots / data cache
        for (int i = 0; i < WARMUP; i++) {
            mutate(menu, mode, i);
            menu.broadcastChanges();
        }
        menu.getSlot(0).set(ItemStack.EMPTY);
        menu.broadcastChanges(); // leave slot 0 empty so the loop below starts clean
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            mutate(menu, mode, i);
            menu.broadcastChanges();
        }
        long elapsed = System.nanoTime() - start;
        long millis = elapsed / 1_000_000;
        LOGGER.info("[menu-bench] mode={} elapsed={}ms", mode, millis);
        return millis;
    }

    private static void mutate(AbstractContainerMenu menu, Mode mode, int iteration) {
        switch (mode) {
            case STATIC -> {
                // never touched
            }
            case SPARSE -> {
                if (iteration % SPARSE_EVERY == 0) {
                    menu.getSlot(0).set(iteration % (SPARSE_EVERY * 2) == 0 ? MUTATION : ItemStack.EMPTY);
                }
            }
            case DENSE_FIRST -> {
                // alternate DIRT / empty so the diff actually fires every broadcast
                menu.getSlot(0).set(iteration % 2 == 0 ? MUTATION : ItemStack.EMPTY);
            }
            case DENSE_LAST -> {
                menu.getSlot(SLOTS - 1).set(iteration % 2 == 0 ? MUTATION : ItemStack.EMPTY);
            }
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage("§e[PRTS] " + message);
        LOGGER.info("{}", message);
    }

    private enum Mode {
        STATIC, SPARSE, DENSE_FIRST, DENSE_LAST
    }

    /** 64-slot menu over a plain container, no synchronizer / listeners. */
    private static final class BenchMenu extends AbstractContainerMenu {

        private BenchMenu(Container container) {
            super(MenuType.GENERIC_9x6, 0);
            for (int i = 0; i < SLOTS; i++) {
                this.addSlot(new Slot(container, i, 0, 0));
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
