package io.izzel.arclight.neoforge;

import io.izzel.arclight.api.Arclight;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.neoforge.mod.NeoForgeArclightServer;
import io.izzel.arclight.neoforge.mod.event.ArclightEventDispatcherRegistry;
import io.izzel.arclight.neoforge.mod.event.EventBusQuery;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.io.PrintStream;

@Mod("arclight")
public class ArclightMod {

    public ArclightMod() {
        ArclightServer.LOGGER.info("mod-load");
        Arclight.setServer(new NeoForgeArclightServer());
        System.setOut(new LoggingPrintStream("STDOUT", System.out, Level.INFO));
        System.setErr(new LoggingPrintStream("STDERR", System.err, Level.ERROR));
        ArclightEventDispatcherRegistry.init();
        // P2 dimension parallelism: bridge the common module's level-tick event
        // callbacks to the real NeoForge EventHooks dispatchers. Wrapped with the
        // no-listener short-circuit (plan §8.4, gated by the entity-tick-event switch
        // as the shared tick-event-family toggle): with nobody on the bus the event
        // construction + empty post is skipped — observationally identical.
        DimensionTickManager.setLevelTickCallbacks(
                (level, hasTimeLeft) -> {
                    if (PRTSFeaturesConfig.eventShortcircuitEntityTickEnabled
                            && !EventBusQuery.hasListeners(LevelTickEvent.Pre.class)) {
                        return;
                    }
                    EventHooks.fireLevelTickPre(level, hasTimeLeft);
                },
                (level, hasTimeLeft) -> {
                    if (PRTSFeaturesConfig.eventShortcircuitEntityTickEnabled
                            && !EventBusQuery.hasListeners(LevelTickEvent.Post.class)) {
                        return;
                    }
                    EventHooks.fireLevelTickPost(level, hasTimeLeft);
                }
        );
    }

    private static class LoggingPrintStream extends PrintStream {

        private final Logger logger;
        private final Level level;

        public LoggingPrintStream(String name, @NotNull OutputStream out, Level level) {
            super(out);
            this.logger = LogManager.getLogger(name);
            this.level = level;
        }

        @Override
        public void println(@Nullable String x) {
            logger.log(level, x);
        }

        @Override
        public void println(@Nullable Object x) {
            logger.log(level, String.valueOf(x));
        }
    }
}
