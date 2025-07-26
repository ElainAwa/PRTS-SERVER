package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

import java.util.Arrays;
import java.util.List;

@ConfigSerializable
public class AsyncSystemSpec {

    @Setting("enabled")
    private boolean enabled = true;

    @Setting("max-threads")
    private int maxThreads = 4;

    @Setting("async-ai-enabled")
    private boolean asyncAIEnabled = true;

    @Setting("async-collision-enabled")
    private boolean asyncCollisionEnabled = true;

    @Setting("async-redstone-enabled")
    private boolean asyncRedstoneEnabled = false;


    @Setting("disable-on-error")
    private boolean disableOnError = true;

    @Setting("event-class-blacklist")
    private List<String> eventClassBlacklist = Arrays.asList(
            "net.minecraftforge.event.TickEvent",
            "net.minecraftforge.event.level.LevelTickEvent",
            "net.minecraftforge.event.entity.living.*"
    );

    @Setting("mod-blacklist")
    private List<String> modBlacklist = Arrays.asList();

    @Setting("strict-class-checking")
    private boolean strictClassChecking = false;

    @Setting("timeout-seconds")
    private int timeoutSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxThreads() {
        return maxThreads;
    }

    public boolean isAsyncAIEnabled() {
        return asyncAIEnabled;
    }

    public boolean isAsyncCollisionEnabled() {
        return asyncCollisionEnabled;
    }

    public boolean isAsyncRedstoneEnabled() {
        return asyncRedstoneEnabled;
    }


    public boolean isDisableOnError() {
        return disableOnError;
    }

    public List<String> getEventClassBlacklist() {
        return eventClassBlacklist;
    }

    public List<String> getModBlacklist() {
        return modBlacklist;
    }

    public boolean isStrictClassChecking() {
        return strictClassChecking;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
