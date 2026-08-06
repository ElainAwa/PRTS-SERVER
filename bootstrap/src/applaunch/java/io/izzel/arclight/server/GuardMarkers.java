package io.izzel.arclight.server;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** ClientModGuard 判定标记/指纹/核心集常量与客户端类前缀判定（v22 内部重构拆分，行为不变）。 */
public final class GuardMarkers {

    // 客户端渲染/界面标记：命中即说明该模组含客户端逻辑
    public static final String[] CLIENT_MARKERS = {
        "net/minecraft/client/main/Main",
        "net/minecraft/client/Minecraft",
        "net/minecraft/client",
        "net/minecraft/client/gui",
        "net/minecraft/client/renderer",
        "net/minecraft/client/model",
        "net/minecraft/client/player",
        "net/minecraft/client/options",
        "net/minecraft/client/KeyMapping",
        "com/mojang/blaze3d",
        "net/minecraftforge/client",
        "net/neoforged/neoforge/client",
        "net.minecraft.client",
        "com.mojang.blaze3d"
    };

    // 服务端逻辑标记（v6 收窄：只用真服务端类）
    public static final String[] SERVER_MARKERS = {
        "net/minecraft/server/MinecraftServer",
        "net.minecraft.server.MinecraftServer",
        "net/minecraft/server/level/ServerLevel",
        "net/minecraft/server/level/ServerPlayer",
        "net/minecraft/server/level/ServerChunkCache",
        "net/minecraft/server/dedicated",
        "net/minecraft/server/network/ServerGamePacketListenerImpl",
        "net/minecraft/server/players",
        "net/minecraft/commands/Commands",
        "net/minecraftforge/server",
        "net/neoforged/neoforge/server",
        "net/minecraftforge/event/server",
        "net/neoforged/neoforge/event/server"
    };

    // 内容注册标记
    public static final String[] CONTENT_MARKERS = {
        "DeferredRegister",
        "RegisterEvent",
        "RegistryEvent"
    };

    // 双端守卫标记：DistExecutor/FMLEnvironment/FabricLoader（模组运行期主动分支检查 dist 后才跑客户端逻辑）。
    // v12 已剔除裸 Dist/EnvType（常量池无区分度，安全集命中率反高于客户端集）；仅作 L3 价值信号。
    public static final String[] DIST_GUARD_MARKERS = {
        "net/minecraftforge/fml/DistExecutor",
        "net/minecraftforge/fml/loading/FMLEnvironment",
        "net/neoforged/fml/DistExecutor",
        "net/neoforged/fml/loading/FMLEnvironment",
        "net/fabricmc/loader/api/FabricLoader"
    };

    // 宽泛守卫标记（@OnlyIn/EnvType/Environment 常量）：弱信号，只证明模组有过 dist 意识，不能判客户端性。
    // 未守卫客户端判定 = hasClient && !hasDistGuard && !hasBroadGuard，只抓零守卫的裸客户端模组。
    public static final String[] BROAD_GUARD_MARKERS = {
        "net/minecraftforge/api/distmarker/Dist",
        "Lnet/minecraftforge/api/distmarker/Dist",
        "net/fabricmc/api/Environment",
        "net/fabricmc/api/EnvType"
    };

    // 缺失客户端类判定用的【二进制类名】前缀（消息里是点号，常量里是斜杠都查一遍）
    public static final String[] CLIENT_CLASS_PREFIX_DOT = {
        "net.minecraft.client.",
        "com.mojang.blaze3d.",
        "net.minecraftforge.client.",
        "net.neoforged.neoforge.client."
    };
    public static final String[] CLIENT_CLASS_PREFIX_SLASH = {
        "net/minecraft/client/",
        "com/mojang/blaze3d/",
        "net/minecraftforge/client/",
        "net/neoforged/neoforge/client/"
    };

    // 运行时定位 offending mod 时跳过的"核心/库"包（这些不是模组自身类）
    public static final String[] CORE_CLASS_PREFIX = {
        "net.minecraft", "net.minecraftforge", "net.neoforged", "com.mojang", "java.",
        "javax.", "sun.", "org.spongepowered", "cpw.mods", "io.izzel.arclight",
        "com.google", "org.apache", "org.objectweb", "org.slf4j", "it.unimi", "oshi.",
        "joptsimple", "net.jodah", "org.yaml", "com.electronwill", "io.netty",
        "org.apache.logging", "org.apache.commons", "com.mojang.brigadier", "com.mojang.math"
    };

    // v4: 已知问题模组"类名指纹"（加速提示，非正确性依赖）
    public static final Map<String, String> KNOWN_BAD_FINGERPRINTS = new LinkedHashMap<String, String>();
    static {
        KNOWN_BAD_FINGERPRINTS.put("io/github/reserveword/imblocker/IMBlocker.class", "纯客户端(IMBlocker,曾致崩服)");
        KNOWN_BAD_FINGERPRINTS.put("com/sighs/generalfeedback/Generalfeedback.class", "纯客户端(GeneralFeedback,含DeathScreen/InventoryScreen/PauseScreen界面mixin,其kubejs兼容运行时引用Screen崩服)");
        KNOWN_BAD_FINGERPRINTS.put("mod/crend/dynamiccrosshair/DynamicCrosshairMod.class", "纯客户端(DynamicCrosshair,毒mixin挂Block曾致崩服)");
        KNOWN_BAD_FINGERPRINTS.put("dev/djefrey/colorwheel/ClrwlBackend.class", "纯客户端(Colorwheel,Flywheel光影兼容层,强依赖Oculus)");
        KNOWN_BAD_FINGERPRINTS.put("optifine/Differ.class", "纯客户端(OptiFine)");
        KNOWN_BAD_FINGERPRINTS.put("i18nupdatemod/I18nUpdateMod.class", "纯客户端(I18nUpdateMod)");
        KNOWN_BAD_FINGERPRINTS.put("dev/tr7zw/skinlayers/SkinLayersMod.class", "纯客户端(SkinLayers3D)");
        KNOWN_BAD_FINGERPRINTS.put("net/xolt/freecam/forge/FreecamForge.class", "纯客户端(Freecam)");
        KNOWN_BAD_FINGERPRINTS.put("net/xolt/freecam/Freecam.class", "纯客户端(Freecam)");
        KNOWN_BAD_FINGERPRINTS.put("com/zergatul/freecam/ModMain.class", "纯客户端(Freecam)");
        KNOWN_BAD_FINGERPRINTS.put("net/irisshaders/iris/Iris.class", "纯客户端(Iris/Oculus光影)");
        KNOWN_BAD_FINGERPRINTS.put("me/flashyreese/mods/sodiumextra/EmbeddiumExtraMod.class", "纯客户端(EmbeddiumExtra)");
        KNOWN_BAD_FINGERPRINTS.put("com/nakuring/enhanced_boss_bars/EnhancedBossBars.class", "纯客户端(EnhancedBossBars)");
        KNOWN_BAD_FINGERPRINTS.put("com/nekotune/battlemusic/BattleMusic.class", "纯客户端(BattleMusic)");
        KNOWN_BAD_FINGERPRINTS.put("me/towdium/jecharacters/JustEnoughCharacters.class", "纯客户端(JustEnoughCharacters)");
        KNOWN_BAD_FINGERPRINTS.put("com/lootbeams/LootBeams.class", "纯客户端(LootBeams)");
        KNOWN_BAD_FINGERPRINTS.put("me/pepperbell/continuity/client/ContinuityClient.class", "纯客户端(Continuity)");
        KNOWN_BAD_FINGERPRINTS.put("eu/midnightdust/cullleaves/neoforge/CullLeavesClientForge.class", "纯客户端(CullLeaves)");
        KNOWN_BAD_FINGERPRINTS.put("dev/imb11/sounds/loaders/neoforge/SoundsNeoForge.class", "纯客户端(Sounds)");
        KNOWN_BAD_FINGERPRINTS.put("com/yshs/searchonmcmod/SearchOnMcmod.class", "纯客户端(SearchOnMcmod)");
        KNOWN_BAD_FINGERPRINTS.put("com/buuz135/smithingtemplateviewer/SmithingTemplateViewer.class", "纯客户端(SmithingTemplateViewer)");
        KNOWN_BAD_FINGERPRINTS.put("com/leclowndu93150/particular/Main.class", "纯客户端(Particular)");
        KNOWN_BAD_FINGERPRINTS.put("dmillerw/menu/MineMenu.class", "纯客户端(MineMenu,按键菜单模组,common_setup 加载 KeyMapping 崩服)");
        KNOWN_BAD_FINGERPRINTS.put("de/keksuccino/fancymenu/FancyMenu.class", "纯客户端(FancyMenu,主菜单/加载界面定制,服务端无意义且可能崩服)");
        KNOWN_BAD_FINGERPRINTS.put("de/keksuccino/konkrete/Konkrete.class", "纯客户端(Konkrete,FancyMenu 的客户端库)");
        KNOWN_BAD_FINGERPRINTS.put("io/github/satxm/mcwifipnp/MCWiFiPnP.class", "纯客户端(MCWiFiPnP,带服务端事件钩子但加载 IntegratedServer 崩服)");
        KNOWN_BAD_FINGERPRINTS.put("team/cagayakegirls/mafglib/MaFgLib.class", "纯客户端(MaFgLib,基于 masa malilib 客户端库,构造加载 Screen 崩服)");
        KNOWN_BAD_FINGERPRINTS.put("org/thinkingstudio/mafglib/MaFgLib.class", "纯客户端(MaFgLib 旧 fork,基于 masa malilib 客户端库)");
        KNOWN_BAD_FINGERPRINTS.put("fi/dy/masa/litematica/Litematica.class", "纯客户端(Litematica/Forgematica,基于 masa malilib 客户端库,构造加载 Screen 崩服)");
        KNOWN_BAD_FINGERPRINTS.put("fi/dy/masa/malilib/MaLiLibConfigs.class", "纯客户端(malilib,masa 客户端配置库)");
        KNOWN_BAD_FINGERPRINTS.put("fi/dy/masa/tweakeroo/Tweakeroo.class", "纯客户端(tweakeroo,masa 客户端模组)");
        KNOWN_BAD_FINGERPRINTS.put("fi/dy/masa/minihud/MiniHud.class", "纯客户端(minihud,masa 客户端模组)");
        KNOWN_BAD_FINGERPRINTS.put("org/thinkingstudio/tweakerge/Tweakerge.class", "纯客户端(Tweakerge,基于 masa tweakeroo 客户端库,构造加载 Screen 崩服)");
        KNOWN_BAD_FINGERPRINTS.put("com/github/leawind/thirdperson/ThirdPerson.class", "纯客户端(Leawind第三人称,摄像机模组)");
        KNOWN_BAD_FINGERPRINTS.put("dzwdz/chat_heads/ChatHeads.class", "纯客户端(聊天头像)");
        KNOWN_BAD_FINGERPRINTS.put("squeek/appleskin/ModConfig.class", "纯客户端(苹果皮,饥饿/食物 HUD)");
        KNOWN_BAD_FINGERPRINTS.put("me/jellysquid/mods/sodium/client/SodiumClientMod.class", "纯客户端(Sodium系:Embeddium/Rubidium)");
        KNOWN_BAD_FINGERPRINTS.put("me/srrapero720/embeddiumplus/EmbeddiumPlus.class", "纯客户端(EmbeddiumPlus)");
        KNOWN_BAD_FINGERPRINTS.put("traben/entity_model_features/EMFManager.class", "纯客户端(EntityModelFeatures)");
        KNOWN_BAD_FINGERPRINTS.put("traben/entity_sound_features/ESF.class", "纯客户端(EntitySoundFeatures)");
        KNOWN_BAD_FINGERPRINTS.put("traben/entity_texture_features/ETF.class", "纯客户端(EntityTextureFeatures)");
        KNOWN_BAD_FINGERPRINTS.put("com/anthonyhilyard/legendarytooltips/LegendaryTooltips.class", "纯客户端(LegendaryTooltips)");
        KNOWN_BAD_FINGERPRINTS.put("com/ishland/c2me/C2MEMod.class", "Fabric专用(C2ME)");
        KNOWN_BAD_FINGERPRINTS.put("org/spongepowered/mod/SpongeMod.class", "核心冲突(SpongeForge与Arclight互斥)");
        KNOWN_BAD_FINGERPRINTS.put("org/spongepowered/common/applaunch/AppLaunch.class", "核心冲突(Sponge与Arclight互斥)");
        KNOWN_BAD_FINGERPRINTS.put("me/wesley1808/servercore/common/ServerCore.class", "Arclight混合端不兼容(ServerCore,其PlayerListMixin与混合端Bukkit重映射冲突,核心已内置等价优化)");
    }

    // v18: 单 jar 内 mixin 类扫描上限，保险丝——超大配置不拖慢启动（两条检测路径共用，避免预算分叉）
    public static final int MIXIN_SCAN_BUDGET = 800;

    // 核心/底层模组，永不动
    public static final Set<String> CORE_MODIDS = new HashSet<String>(Arrays.asList(
        "minecraft", "forge", "neoforge", "fml", "mcp", "arclight", "luminara", "forgefml"
    ));

    // 已核实为双端（含服务端逻辑）的模组，引用分析无法区分，故内置保护，避免误删导致缺核心模组
    // create_hypertube 必须保留：v29 曾因「detectPoisonMixin 治本」将其移除，但 Goal B（quarantineClientOnly）
    // 的 clientOnly 判定仍依赖本集豁免；其含 client 段 mixin 会被判 clientOnly，漏在本集即被 Goal B 误隔离。
    public static final Set<String> BUILTIN_SAFE = new HashSet<String>(Arrays.asList(
        "zenith", "cloth_config", "cloth-config", "resourcefulconfig", "resourceful-config",
        "ae2ct", "ae2", "create", "create_hypertube"
    ));

    private GuardMarkers() {}

    /** 缺失客户端类判定：消息含任一客户端类前缀即真（strict：仅客户端类前缀才算，避免吞掉无关崩溃）。 */
    public static boolean containsClientPrefix(String msg) {
        for (String p : CLIENT_CLASS_PREFIX_DOT) if (msg.contains(p)) return true;
        for (String p : CLIENT_CLASS_PREFIX_SLASH) if (msg.contains(p)) return true;
        return false;
    }

    /** 从文本中提取首个客户端类名（到空白/引号为止）。 */
    public static String extractClientClass(String line) {
        String[][] groups = {CLIENT_CLASS_PREFIX_SLASH, CLIENT_CLASS_PREFIX_DOT};
        for (String[] g : groups) {
            for (String p : g) {
                int i = line.indexOf(p);
                if (i >= 0) {
                    int e = i;
                    while (e < line.length() && " \t\"'".indexOf(line.charAt(e)) < 0) e++;
                    return line.substring(i, e);
                }
            }
        }
        return "unknown-client-class";
    }

    /** 栈帧类名是否属于核心/库包（定位 offending mod 时跳过）。 */
    public static boolean isCoreClass(String cn) {
        for (String p : CORE_CLASS_PREFIX) if (cn.startsWith(p)) return true;
        return false;
    }
}
