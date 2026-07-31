package io.izzel.arclight.server;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.ProcessBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;

/**
 * 启动前扫描 mods 目录，识别并隔离"客户端专用模组"，避免其被加载进服务端导致崩溃（参考 IMBlocker 案例）。
 *
 * v10 架构（自愈式 + 状态记忆，发布后健壮性）：
 *  0) 预扫描只做【高置信】隔离（精确率优先）。模糊双端模组(hasClient && hasServer 但无内容/无 common mixin)
 *     本就不预隔离，交给运行时自愈兜底——避免误伤 spark/ae2ct 这类双端模组。
 *  1) 运行时自愈：Launcher.main 用 try/catch 包住 Main_Forge.main。一旦捕获到"缺失客户端类"
 *     (NoClassDefFoundError/ClassNotFoundException 且缺失类 ∈ net/minecraft/client|com/mojang/blaze3d|
 *     net/minecraftforge/client|net/neoforged/neoforge/client) → 从栈定位 offending mod →
 *     隔离 → 新 JVM 进程重启（带 PRTS_GUARD_RETRY 计数，上限 MAX_RESTART）。
 *     这样【未知客户端模组不需要任何指纹】，JVM 自己暴露，彻底解决"指纹列不全"死穴。
 *  2) 状态记忆 _guard_state.json：记录 quarantined(我们隔离过的 modId) 与 insisted_failed(用户加回后仍崩)。
 *     用户把隔离模组加回 mods/ → 视为显式覆盖，预扫描不再隔离它；若它真崩，自愈再隔离并计次，连崩告警。
 *     彻底解决"误删死循环"死穴。
 *  3) 白名单（逃生口）：clientside-guard.json 的 allowlist（及兼容 prts.yml guard.allowlist）→ 强制保留，
 *     跳过一切判定（即便它真缺客户端类也交给 FML 原生报错）。
 *  4) 指纹降级：KNOWN_BAD_FINGERPRINTS 仍为【加速提示】——命中即预隔离、少崩一圈；删光指纹也不影响正确性。
 *
 * v12 P0 判定转向（基于 283 个真实 jar 的三方对账实测，见 docs/clientmodguard-v12-precision-plan.md）：
 *  判定对象从「这是不是客户端模组」改为「这个模组会不会让服务端崩」。实测 209 个正常运行的模组里
 *  81% 都含客户端代码——「客户端性」与「危害性」是两回事，v11 把二者混为一谈，才会既误杀又漏检。
 *  a) 删除 hasDistGuard / hasKjsPlugin 对 suspect 的豁免（实测该信号在安全集命中率 72% > 客户端集 61%，
 *     方向甚至是反的，当免死金牌造成 61% 漏检）；二者降级为 L3 价值信号。
 *  b) DIST_GUARD_MARKERS 剔除裸 Dist / EnvType（@OnlyIn 注解本身就写入常量池，无区分度）。
 *  c) 【核心】证据不足 → AMBER = 保留 + 观察 + 报告，不再有罪推定。只有 L1 硬证据
 *     （黑名单 / 类名指纹 / mods.toml 自声明 CLIENT）才自动隔离。历史 5 次误删由此根除。
 *  d) trustedModList：权威参考清单否决启发式隔离，但不否决硬证据，且仍完整跑判定进报告。
 *  e) 隔离动作原子化（实测发现 3 个 jar 同时存在于 mods/ 与隔离区，隔离形同虚设）。
 *  f) _guard_precheck.log 输出每模组 SIG 明细行，任何一次判定都可审计。
 *
 * 历史判定策略（保留）：
 *  v7 依赖一致性闭包 + common mixin 精确化；v8 anyPoison 毒 mixin 否决；v9 GeneralFeedback/ServerCore 指纹；
 *  v9b MineMenu/FancyMenu/Konkrete 指纹 + _guard_precheck.log 落盘；v9c ae2ct 白名单 + mcwifipnp 指纹；
 *  v9d MaFgLib/Tweakerge/leawind/chat_heads/appleskin 指纹。详见 git 历史。
 *
 * 隔离只是把文件移动到 _quarantine/clientside/，可随时移回。
 */
public final class ClientModGuard {

    private static final Path QUARANTINE_DIR = Paths.get("_quarantine", "clientside");
    // 运行时隔离失败（Windows 文件占用）时的同目录改名后缀；下次启动预扫描会把它真正移走。
    private static final String PENDING_SUFFIX = ".prts-quarantined";
    private static final Path PRECHECK_LOG = Paths.get("_guard_precheck.log");
    private static Path MODS_DIR;

    // 自愈重启上限（单次会话最多自动重启次数，防级联/失控）
    private static final int MAX_RESTART = 5;
    private static final String RETRY_ENV = "PRTS_GUARD_RETRY";

    // 客户端渲染/界面标记：命中即说明该模组含客户端逻辑
    private static final String[] CLIENT_MARKERS = {
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
    private static final String[] SERVER_MARKERS = {
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
    private static final String[] CONTENT_MARKERS = {
        "DeferredRegister",
        "RegisterEvent",
        "RegistryEvent"
    };

    // 双端守卫标记：模组在【运行期主动分支】检查 dist 后才跑客户端逻辑
    // (如 FTB Ultimine Indicator 用 DistExecutor.safeRunWhenOn(Dist.CLIENT) 守卫)。
    //
    // v12 P0-2 信号提纯（实测依据）：原先包含裸 `Dist` / `EnvType` 是【严重错误】——
    //   `@OnlyIn(Dist.CLIENT)` 注解本身就往字节码常量池写入 `net/minecraftforge/api/distmarker/Dist`，
    //   凡是规矩标注客户端代码的模组（【包括纯客户端模组】）全部命中。
    //   实测：隔离集(74 已知客户端) 61% 命中，保留集(209 已知安全) 72% 命中——信号方向甚至是【反】的。
    //   v11 把它当免死金牌，等于给 61% 的真客户端模组发通行证（oculus 簇漏网即源于此）。
    // 故只保留【真正表示运行期分支】的类：DistExecutor / FMLEnvironment / FabricLoader。
    //
    // 注意：本标记在 v12 中【不再豁免 suspect 判定】，仅作为 L3 价值信号之一（见 decide()）。
    private static final String[] DIST_GUARD_MARKERS = {
        "net/minecraftforge/fml/DistExecutor",
        "net/minecraftforge/fml/loading/FMLEnvironment",
        "net/neoforged/fml/DistExecutor",
        "net/neoforged/fml/loading/FMLEnvironment",
        "net/fabricmc/loader/api/FabricLoader"
    };

    // 缺失客户端类判定用的【二进制类名】前缀（消息里是点号，常量里是斜杠都查一遍）
    private static final String[] CLIENT_CLASS_PREFIX_DOT = {
        "net.minecraft.client.",
        "com.mojang.blaze3d.",
        "net.minecraftforge.client.",
        "net.neoforged.neoforge.client."
    };
    private static final String[] CLIENT_CLASS_PREFIX_SLASH = {
        "net/minecraft/client/",
        "com/mojang/blaze3d/",
        "net/minecraftforge/client/",
        "net/neoforged/neoforge/client/"
    };

    // 运行时定位 offending mod 时跳过的"核心/库"包（这些不是模组自身类）
    private static final String[] CORE_CLASS_PREFIX = {
        "net.minecraft", "net.minecraftforge", "net.neoforged", "com.mojang", "java.",
        "javax.", "sun.", "org.spongepowered", "cpw.mods", "io.izzel.arclight",
        "com.google", "org.apache", "org.objectweb", "org.slf4j", "it.unimi", "oshi.",
        "joptsimple", "net.jodah", "org.yaml", "com.electronwill", "io.netty",
        "org.apache.logging", "org.apache.commons", "com.mojang.brigadier", "com.mojang.math"
    };

    // v4: 已知问题模组"类名指纹"（加速提示，非正确性依赖）
    private static final Map<String, String> KNOWN_BAD_FINGERPRINTS = new LinkedHashMap<>();
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

    // 核心/底层模组，永不动
    private static final Set<String> CORE_MODIDS = new HashSet<String>(Arrays.asList(
        "minecraft", "forge", "neoforge", "fml", "mcp", "arclight", "luminara", "forgefml"
    ));

    // 已核实为双端（含服务端逻辑）的模组，引用分析无法区分，故内置保护，避免误删导致缺核心模组
    private static final Set<String> BUILTIN_SAFE = new HashSet<String>(Arrays.asList(
        "zenith", "cloth_config", "cloth-config", "resourcefulconfig", "resourceful-config",
        "ae2ct", "ae2", "create"
    ));

    private static volatile boolean SELF_HEALED = false;
    private static String[] LAUNCH_ARGS = new String[0];
    private static long BOOT_TIME = System.currentTimeMillis();

    public static void run() {
        run(new String[0]);
    }

    public static void run(String[] args) {
        if (args != null) LAUNCH_ARGS = args;
        BOOT_TIME = System.currentTimeMillis();
        try {
            scan();
        } catch (Throwable t) {
            System.err.println("[PRTS] 客户端模组预检异常（已跳过，不影响启动）: " + t);
            note("EXCEPTION " + t);
        }
        // v10: vanilla Main.main 会吞掉模组加载异常后正常退出 JVM（不向上抛），
        // 所以运行时自愈的主路径是 shutdown hook：JVM 退出时检查本次启动产生的崩溃报告。
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        shutdownHeal();
                    } catch (Throwable ignored) {}
                }
            }, "PRTS-Guard-Healer"));
        } catch (Throwable ignored) {}

        // v10b: 任意线程感知运行时自愈——覆盖"子线程懒加载客户端类失败"盲区（主线程 try/catch 与 shutdown hook 都抓不到）。
        // 仅当异常含客户端类缺失前缀才干预；非客户端类失败保留 JVM 默认行为（打印栈），不吞异常。
        final Thread.UncaughtExceptionHandler prevHandler = Thread.getDefaultUncaughtExceptionHandler();
        try {
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        if (isClientClassMissing(e)) {
                            note("UNCAUGHT_CLIENT_FAILURE thread=" + t.getName() + " " + summarize(e));
                            onUncaughtClientFailure(e);
                            return;
                        }
                    } catch (Throwable ignored) {}
                    // 非客户端类失败：保留 JVM 默认行为，不干预（避免吞掉正常异常）
                    if (prevHandler != null) prevHandler.uncaughtException(t, e);
                    else e.printStackTrace();
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * shutdown hook 自愈：JVM 退出时，若发现本次启动之后新生成的崩溃报告中
     * 存在"模组因客户端类缺失加载失败"，则隔离 offending mod 并拉起新 JVM。
     * 正常关服（/stop、Ctrl+C）不会有此类报告，钩子静默返回。
     * 注意：hook 内严禁 System.exit（会死锁）。
     */
    static void shutdownHeal() {
        if (SELF_HEALED) return; // handleCrash 已处理过
        List<String[]> failures = parseCrashReportClientFailures(BOOT_TIME);
        if (failures.isEmpty()) return;
        note("SHUTDOWN_HEAL detected clientFailures=" + failures.size());

        Path dir = MODS_DIR != null && Files.isDirectory(MODS_DIR)
            ? MODS_DIR : Paths.get(System.getProperty("fml.modsDir", "mods"));
        List<Path> jars = new ArrayList<Path>();
        try { collectJars(dir, jars); } catch (IOException ignored) {}

        Map<String, Object[]> offenders = new LinkedHashMap<String, Object[]>();
        for (String[] pr : failures) {
            Path jar = findJarByModId(jars, pr[0]);
            if (jar != null) offenders.put(pr[0], new Object[]{jar, "runtime: " + pr[1]});
        }
        if (offenders.isEmpty()) {
            note("SHUTDOWN_HEAL cannot locate jars for failures");
            return;
        }
        if (!quarantineOffenders(offenders)) return;

        int retry = currentRetry() + 1;
        if (retry > MAX_RESTART) {
            System.err.println("[PRTS] 自愈重启次数已达上限(" + MAX_RESTART + ")，停止自动重启。请检查并清理 mods/ 中的客户端模组。");
            note("SHUTDOWN_HEAL retry limit reached");
            return;
        }
        List<String> cmd = buildCommand(LAUNCH_ARGS);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        pb.environment().put(RETRY_ENV, String.valueOf(retry));
        try {
            pb.start();
            System.out.println("[PRTS] 自愈完成，已拉起新服务端进程（第 " + retry + " 次自动重启）。");
            note("SHUTDOWN_HEAL respawned retry=" + retry);
        } catch (IOException e) {
            System.err.println("[PRTS] 自愈重启失败（无法启动新 JVM）: " + e);
            note("SHUTDOWN_HEAL respawn failed " + e);
        }
    }

    /** 隔离 offenders 并更新状态文件；全部成功返回 true。 */
    private static boolean quarantineOffenders(Map<String, Object[]> offenders) {
        GuardState state = GuardState.load();
        for (Map.Entry<String, Object[]> e : offenders.entrySet()) {
            String modId = e.getKey();
            Path jar = (Path) e.getValue()[0];
            String reason = (String) e.getValue()[1];
            String digest = sha1(jar); // 隔离前取摘要（隔离后文件已移走）
            try {
                runtimeQuarantine(jar);
            } catch (Throwable qe) {
                System.err.println("[PRTS] 自愈隔离模组失败: " + qe);
                note("QUARANTINE_FAIL " + jar + " " + qe);
                return false;
            }
            state.quarantined.put(modId, new GuardState.Info(digest, reason, System.currentTimeMillis()));
            int fails = 0;
            GuardState.Info fi = state.insistedFailed.get(modId);
            if (fi != null) fails = (int) fi.at; // insistedFailed 的 at 字段复用存储连崩计数
            fails++;
            state.insistedFailed.put(modId, new GuardState.Info(digest, "insisted crash x" + fails, fails));
            System.out.println("[PRTS] 自愈：已隔离导致崩溃的客户端模组 " + jar.getFileName() + " (modId=" + modId + ")");
            note("SELFHEAL " + jar.getFileName() + " modId=" + modId + " " + reason + " insistedFails=" + fails);
            if (fails >= 2) {
                System.err.println("[PRTS] 警告：模组 '" + modId + "' 已被多次隔离（疑似必须为客户端）。后续每次启动都会自动隔离它。");
                System.err.println("[PRTS] 如需强制保留，请在 clientside-guard.json 的 allowlist 加入 \"" + modId + "\"（或 prts.yml guard.allowlist）；否则请将其移出 mods/。");
            }
        }
        state.save();
        return true;
    }

    /**
     * 运行时自愈入口：由 Launcher.main 在 try/catch 中捕获 Main_Forge.main 抛出的异常后调用。
     * 若异常是"缺失客户端类"导致，则定位并隔离 offending mod 并自动重启（不返回）。
     * 否则直接返回，由调用方继续向上抛（正常崩溃）。
     */
    public static synchronized void handleCrash(Throwable t, String[] args) {
        boolean direct = isClientClassMissing(t);
        boolean modLoadFail = isModLoadingFailed(t);
        if (!direct && !modLoadFail) return; // 无关崩溃，交还调用方正常抛出
        note("CRASH " + (direct ? "CLIENT_DIRECT" : "MOD_LOADING_FAILED") + " " + summarize(t));

        Path dir = MODS_DIR != null && Files.isDirectory(MODS_DIR)
            ? MODS_DIR : Paths.get(System.getProperty("fml.modsDir", "mods"));
        List<Path> jars = new ArrayList<Path>();
        try { collectJars(dir, jars); } catch (IOException ignored) {}

        // offenders: modId -> {jar, reason}
        Map<String, Object[]> offenders = new LinkedHashMap<String, Object[]>();

        // 路径1: 异常链本身含客户端类名（NoClassDefFoundError 直达 main）
        if (direct) {
            Path mod = locateOffendingMod(t);
            if (mod != null) {
                String modId;
                try { modId = detectModMeta(mod).modId; } catch (Throwable e) { modId = mod.getFileName().toString(); }
                offenders.put(modId, new Object[]{mod, "runtime: " + missingClassName(t)});
            }
        }
        // 路径2: FML 吞掉真实异常只抛 "Mod Loading has failed" → 解析最新 crash report
        if (offenders.isEmpty()) {
            List<String[]> parsed = parseCrashReportClientFailures(BOOT_TIME);
            for (String[] pr : parsed) {
                Path jar = findJarByModId(jars, pr[0]);
                if (jar != null) offenders.put(pr[0], new Object[]{jar, "runtime: " + pr[1]});
            }
        }
        if (offenders.isEmpty()) {
            if (direct) {
                System.err.println("[PRTS] 检测到客户端类缺失导致崩溃，但无法定位具体模组。");
                System.err.println("[PRTS] 请检查 _guard_precheck.log 或手动将疑似客户端模组移出 mods/ 目录。");
                note("CRASH_UNLOCATED " + summarize(t));
                System.exit(1);
            }
            return; // Mod Loading failed 但与客户端类无关 → 正常崩溃，交还调用方
        }

        if (!quarantineOffenders(offenders)) {
            System.exit(1);
            return;
        }
        SELF_HEALED = true; // 防止 shutdown hook 重复处理
        System.out.println("[PRTS] 自愈完成（隔离 " + offenders.size() + " 个模组），自动重启服务端...");
        restart(args);
    }

    /**
     * 子线程感知运行时自愈：复用 v10 既有定位/隔离/重启机器，对任意线程的"客户端类加载失败"兜底。
     * 盲区 3 根因：forgematica 在 Worker-Main 子线程懒加载 ClientLevel 失败，不冒泡主线程、不写 crash-report，
     * 主线程 try/catch(handleCrash) 与 shutdown hook(shutdownHeal) 都抓不到 → 服务端静默带病跑。本方法由其未捕获异常处理器调用。
     */
    public static synchronized void onUncaughtClientFailure(Throwable t) {
        if (SELF_HEALED) return; // handleCrash 已处理 / 已重启，避免重入与级联重复隔离
        Path mod = locateOffendingMod(t);
        if (mod == null) {
            note("UNCAUGHT_UNLOCATED " + summarize(t));
            System.exit(1);
            return;
        }
        Map<String, Object[]> offenders = new LinkedHashMap<String, Object[]>();
        String modId;
        try { modId = detectModMeta(mod).modId; } catch (Throwable e) { modId = mod.getFileName().toString(); }
        offenders.put(modId, new Object[]{mod, "runtime-uncaught: " + missingClassName(t)});
        if (!quarantineOffenders(offenders)) { System.exit(1); return; }
        SELF_HEALED = true; // 防止 shutdown hook 重复处理
        System.out.println("[PRTS] 自愈（子线程捕获）完成（隔离 " + offenders.size()
            + " 个模组），自动重启服务端...");
        note("UNCAUGHT_SELFHEAL " + mod.getFileName() + " modId=" + modId);
        restart(LAUNCH_ARGS);
    }

    /** FML/NeoForge 在模组加载失败时吞掉真实异常，仅抛通用消息。 */
    static boolean isModLoadingFailed(Throwable t) {
        Throwable c = t;
        while (c != null) {
            String msg = c.getMessage();
            if (msg != null && (msg.contains("Mod Loading has failed")
                || msg.contains("Loading errors encountered")
                || msg.contains("mod loading error"))) return true;
            c = c.getCause();
        }
        return false;
    }

    /** 解析最新 crash report（须晚于 sinceMillis 生成），返回 {modId, missingClass} 列表（仅客户端类缺失导致的加载失败）。 */
    static List<String[]> parseCrashReportClientFailures(long sinceMillis) {
        List<String[]> out = new ArrayList<String[]>();
        try {
            Path dir = Paths.get("crash-reports");
            if (!Files.isDirectory(dir)) return out;
            Path newest = null;
            long best = 0L;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    if (!n.endsWith(".txt")) continue;
                    long m = Files.getLastModifiedTime(p).toMillis();
                    if (m > best) { best = m; newest = p; }
                }
            }
            if (newest == null) return out;
            if (best < sinceMillis) return out; // 只认本次启动之后生成的报告
            List<String> lines = Files.readAllLines(newest, StandardCharsets.UTF_8);
            Pattern fp = Pattern.compile("Failure message:.*\\(([\\w .-]+)\\) has failed to load correctly");
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = fp.matcher(lines.get(i));
                if (!m.find()) continue;
                String modId = m.group(1).trim();
                String missing = null;
                int end = Math.min(i + 8, lines.size());
                for (int j = i; j < end; j++) {
                    String l = lines.get(j);
                    if (containsClientPrefix(l) && (l.contains("invalid dist")
                        || l.contains("NoClassDefFoundError") || l.contains("ClassNotFoundException"))) {
                        missing = extractClientClass(l);
                        break;
                    }
                }
                if (missing != null) out.add(new String[]{modId, missing});
            }
            if (!out.isEmpty()) note("CRASHREPORT " + newest.getFileName() + " clientFailures=" + out.size());
        } catch (Throwable e) { note("CRASHREPORT_PARSE_FAIL " + e); }
        return out;
    }

    private static String extractClientClass(String line) {
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

    private static Path findJarByModId(List<Path> jars, String modId) {
        for (Path jar : jars) {
            try {
                if (modId.equals(detectModMeta(jar).modId)) return jar;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ===================== 预扫描 =====================

    private static void scan() throws IOException {
        String prop = System.getProperty("fml.modsDir");
        MODS_DIR = prop != null ? Paths.get(prop) : Paths.get("mods");
        if (!Files.isDirectory(MODS_DIR)) return;

        GuardState state = GuardState.load();
        Config cfg = Config.load();

        // v10: 清理上轮运行时改名遗留的 .prts-quarantined（同目录改名兜底，本次真正移走）
        cleanupPending();

        List<Path> jars = new ArrayList<Path>();
        collectJars(MODS_DIR, jars);
        try { Files.deleteIfExists(PRECHECK_LOG); } catch (IOException ignored) {}
        note("START modsDir=" + MODS_DIR.toAbsolutePath() + " jars=" + jars.size()
            + " autoQuarantine=" + cfg.autoQuarantine + " prune=" + cfg.pruneHarmlessClientMods);
        if (!cfg.trustedSources.isEmpty()) {
            System.out.println("[PRTS] 客户端模组预检: 已载入权威参考清单 " + String.join(", ", cfg.trustedSources)
                + "（合计 " + cfg.trustedIds.size() + " 个 modId / " + cfg.trustedNames.size() + " 个文件名）");
            note("TRUSTED_SOURCES " + String.join(", ", cfg.trustedSources));
        }

        // v12 P0-6b：先清除「隔离未生效」的残留，否则后续判定基于一份被污染的模组集
        reconcileQuarantineDuplicates(jars);

        Decision d = decide(jars, cfg, state);

        if (!d.byFingerprint.isEmpty()) {
            System.out.println("[PRTS] 类名指纹命中（已知客户端/冲突模组）: ");
            for (String s : d.byFingerprint) System.out.println("[PRTS]   - " + s);
        }
        boolean moved = false;
        for (Path jar : d.toQuarantine) {
            moved = true;
            System.out.println("[PRTS] 客户端模组预检: 隔离疑似客户端专用模组 -> " + jar.getFileName());
            quarantine(jar);
            String id = detectModMeta(jar).modId;
            state.quarantined.put(id, new GuardState.Info(sha1(jar), "prescan", System.currentTimeMillis()));
        }
        if (!d.chained.isEmpty()) {
            System.out.println("[PRTS] 依赖断链连坐隔离（其必需依赖已被隔离）: ");
            for (String s : d.chained) System.out.println("[PRTS]   - " + s);
        }
        if (!d.keptByDep.isEmpty()) {
            System.out.println("[PRTS] 因被服务端模组依赖而保留（避免缺依赖）: " + String.join(", ", d.keptByDep));
        }
        if (!d.brokenDeps.isEmpty()) {
            System.err.println("[PRTS] 注意：以下依赖关系因目标模组命中硬证据（已确证客户端/崩服）而【未】回补——");
            System.err.println("[PRTS]       依赖方可能功能异常，但把崩服模组放回会导致整包起不来，故优先保证可启动：");
            for (String s : d.brokenDeps) {
                System.err.println("[PRTS]   - " + s);
                note("  BROKEN_DEP " + s);
            }
        }
        if (!d.restored.isEmpty()) {
            System.out.println("[PRTS] 曾被隔离、已被用户加回的模组（尊重用户选择，跳过预隔离）: " + String.join(", ", d.restored));
            for (String s : d.restored) note("  RESTORED " + s);
        }
        if (!d.trustedConflict.isEmpty()) {
            System.err.println("[PRTS] 警告：以下模组虽在权威参考清单中，但命中了硬证据仍被隔离——");
            System.err.println("[PRTS]       说明该参考清单自身混有客户端模组，建议人工复核清单本身：");
            for (String s : d.trustedConflict) {
                System.err.println("[PRTS]   - " + s);
                note("  TRUSTED_CONFLICT " + s);
            }
        }
        if (!d.amber.isEmpty()) {
            System.out.println("[PRTS] 客户端模组预检: " + d.amber.size()
                + " 个模组含客户端代码但无确证危害证据，已【保留】并列入观察名单（明细见 _guard_precheck.log 的 AMBER 行）");
            if (!cfg.pruneHarmlessClientMods) {
                System.out.println("[PRTS]       如需一并清理，请在 clientside-guard.json 设 \"pruneHarmlessClientMods\": true");
            }
            for (String s : d.amber) note("  AMBER " + s);
        }
        if (!cfg.autoQuarantine && !d.reported.isEmpty()) {
            System.out.println("[PRTS] autoQuarantine=false，以下仅报告未隔离: " + String.join(", ", d.reported));
        }
        if (moved) {
            System.out.println("[PRTS] 已将疑似客户端模组隔离至 " + QUARANTINE_DIR.toAbsolutePath());
            System.out.println("[PRTS] 若系误判，请移回 mods 并在 clientside-guard.json 的 allowlist 加入其 modId/文件名");
        } else if (d.toQuarantine.isEmpty() && d.reported.isEmpty()) {
            System.out.println("[PRTS] 客户端模组预检：未发现疑似客户端专用模组");
        }
        System.out.println("[PRTS] 客户端模组预检结束: 隔离 " + d.toQuarantine.size()
            + " 个 / 保留 " + d.keepCount + " 个，继续启动服务端...");
        note("DONE quarantined=" + d.toQuarantine.size() + " kept=" + d.keepCount
            + " fingerprint=" + d.byFingerprint.size() + " chained=" + d.chained.size()
            + " reported=" + d.reported.size() + " amber=" + d.amber.size()
            + " trustedConflict=" + d.trustedConflict.size()
            + (moved ? " (moved)" : (d.toQuarantine.isEmpty() && d.reported.isEmpty() ? " (none)" : " (report-only)")));
        for (String s : d.byFingerprint) note("  FINGERPRINT " + s);
        for (Path p : d.toQuarantine) note("  QUARANTINE " + p.getFileName());
        for (String s : d.chained) note("  CHAINED " + s);
        for (String s : d.keptByDep) note("  KEPT_BY_DEP " + s);
        state.save(); // v10c: 始终落盘（含 scan_cache），即便未隔离也缓存扫描结果供下次启动命中
    }

    /** 把上次运行时改名兜底的 .prts-quarantined 真正移入隔离区。 */
    private static void cleanupPending() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(MODS_DIR)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.toLowerCase(Locale.ROOT).endsWith(PENDING_SUFFIX)) {
                    Path target = QUARANTINE_DIR.resolve(name.substring(0, name.length() - PENDING_SUFFIX.length()));
                    Files.createDirectories(target.getParent());
                    if (Files.exists(target)) Files.delete(target);
                    try {
                        Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {
                        // 仍占用则留到下轮
                    }
                    note("  PENDING_MOVE " + p.getFileName());
                }
            }
        } catch (IOException ignored) {}
    }

    /** 判定但不移动，供测试与真实执行共用。 */
    public static Decision decide(List<Path> jars, Config cfg, GuardState state) {
        Decision d = new Decision();

        int total = jars.size();
        long t0 = System.currentTimeMillis();
        System.out.println("[PRTS] 客户端模组预检: 开始扫描 " + total + " 个模组 jar（大整合包约需数秒~数十秒，请勿中断）...");
        Map<Path, ModMeta> meta = new LinkedHashMap<Path, ModMeta>();
        Map<Path, ScanResult> result = new LinkedHashMap<Path, ScanResult>();
        int done = 0;
        int cacheHits = 0;
        Set<String> liveKeys = new HashSet<String>();
        for (Path jar : jars) {
            String fileName = jar.getFileName().toString();
            if (!fileName.toLowerCase().endsWith(".jar")) continue;
            meta.put(jar, detectModMeta(jar));
            // v10c: 扫描结果缓存——jar 内容(文件名:大小:修改时间)未变则复用，跳过全量字节扫描
            String key = cacheKey(jar);
            liveKeys.add(key);
            ScanResult cached = state.scanCache.get(key);
            if (cached != null) {
                result.put(jar, cached);
                cacheHits++;
            } else {
                result.put(jar, scanJarFull(jar));
                state.scanCache.put(key, result.get(jar));
            }
            done++;
            if (done % 50 == 0) {
                long el = System.currentTimeMillis() - t0;
                System.out.println("[PRTS] 客户端模组预检: 已扫描 " + done + "/" + total + "（" + (el / 1000) + "s）...");
            }
        }
        // 清理已不存在 jar 的失效缓存，避免 _guard_state.json 无限膨胀
        state.scanCache.keySet().retainAll(liveKeys);
        if (cacheHits > 0) {
            System.out.println("[PRTS] 客户端模组预检: 命中扫描缓存 " + cacheHits + "/" + done + " 个，跳过全量扫描");
        }
        System.out.println("[PRTS] 客户端模组预检: 扫描完成 " + done + "/" + total
            + "，耗时 " + (System.currentTimeMillis() - t0) + " ms，开始判定");

        Map<String, Set<String>> deps = new HashMap<String, Set<String>>();
        for (Map.Entry<Path, ModMeta> e : meta.entrySet()) {
            deps.put(e.getValue().modId, e.getValue().dependencies);
        }

        for (Path jar : jars) {
            String fileName = jar.getFileName().toString().toLowerCase();
            if (!fileName.endsWith(".jar")) continue;
            ModMeta m = meta.get(jar);
            ScanResult r = result.get(jar);
            if (CORE_MODIDS.contains(m.modId)) {
                d.keepCount++;
                sig(jar.getFileName().toString(), m.modId, m, r, false, "KEEP", "L0/core");
                continue;
            }

            String id = m.modId;
            String fn = jar.getFileName().toString();
            boolean inWhite = cfg.whitelist.contains(id) || cfg.whitelist.contains(fileName);
            boolean inBlack = cfg.blacklist.contains(id) || cfg.blacklist.contains(fileName);

            // v10: 用户加回的模组（曾在 quarantined 记忆里、本次又出现在 mods/）= 显式覆盖，跳过一切预隔离判定。
            // 若它是真客户端模组，运行时自愈会再抓它；若系误伤（如 ae2ct），则保留成功。
            boolean restored = state.quarantined.containsKey(id);
            if (restored) {
                d.keepCount++;
                d.restored.add(fn);
                GuardState.Info fi = state.insistedFailed.get(id);
                if (fi != null && fi.at >= 2) {
                    // 连崩告警：shutdown hook 里的 System.err 会被 log4j 关闭吞掉，故在预扫描阶段（控制台可见）重复告警
                    System.err.println("[PRTS] 警告：模组 '" + id + "' (" + fn + ") 曾连续 " + fi.at + " 次因缺失客户端类崩溃后被自动隔离，现已被加回。");
                    System.err.println("[PRTS]       它几乎可以确定是客户端专用模组。若坚持保留请加入 clientside-guard.json 的 allowlist；否则请将其移出 mods/，避免反复崩溃重启。");
                    note("INSISTED_WARN " + fn + " modId=" + id + " fails=" + fi.at);
                }
                sig(fn, id, m, r, cfg.isTrusted(id, fn), "KEEP", "L0/user-restored");
                continue;
            }

            boolean envClient = "CLIENT".equalsIgnoreCase(m.environment);
            boolean envServer = "SERVER".equalsIgnoreCase(m.environment) || "BOTH".equalsIgnoreCase(m.environment);

            // v12 P0-1：hasDistGuard / hasKjsPlugin 不再豁免 suspect。
            // 实测（74 已知客户端 vs 209 已知安全）二者在两集分布几乎相同，作为「是不是客户端模组」的判据完全无效，
            // 当免死金牌用直接造成 61% 漏检（oculus 簇即由此漏网并硬崩 ModSorter）。
            // 二者降级为下方 L3 的 VALUE 信号：只回答「服务端是否需要它」，不回答「它是否客户端」。
            boolean suspect = r.hasClient && !r.hasServer && !r.hasContent && !r.hasCommonMixin;
            // L3 价值信号：data 内容（实测保留集 52% vs 隔离集 1%，高精度）/ KubeJS 插件 / 运行期 dist 分支
            boolean hasValue = r.hasContent || r.hasKjsPlugin || r.hasDistGuard;
            boolean trusted = cfg.isTrusted(id, fn);

            String fpReason = matchFingerprint(jar);
            if (fpReason != null && !inWhite) {
                d.toQuarantine.add(jar);
                d.hardQuarantined.add(jar);
                d.byFingerprint.add(fn + " [" + fpReason + "]");
                // 权威清单不否决已确证的硬证据，但要显式告警：说明该参考清单自身含客户端模组
                if (trusted) d.trustedConflict.add(fn + " [类名指纹: " + fpReason + "]");
                sig(fn, id, m, r, trusted, "QUARANTINE", "L1/fingerprint");
                continue;
            }

            Verdict v;
            String src;
            if (inBlack) {
                v = Verdict.QUARANTINE;
                src = "L0/blacklist";
                if (trusted) d.trustedConflict.add(fn + " [黑名单]");
            } else if (inWhite || BUILTIN_SAFE.contains(id) || envServer) {
                v = Verdict.KEEP;
                src = inWhite ? "L0/allowlist"
                    : (envServer ? "L1/declared-" + m.environment.toLowerCase(Locale.ROOT) : "L0/builtin-safe");
            } else if (envClient) {
                // L1 硬证据：模组在 mods.toml 里自己声明 CLIENT。这是模组作者的明示，可信度最高。
                v = cfg.autoQuarantine ? Verdict.QUARANTINE : Verdict.REPORT;
                src = "L1/declared-client";
                if (trusted && v == Verdict.QUARANTINE) d.trustedConflict.add(fn + " [mods.toml 自声明 CLIENT]");
            } else if (suspect) {
                boolean requiredByKept = isRequiredByKept(id, deps, meta, result, cfg);
                if (hasValue) {
                    v = Verdict.KEEP;
                    src = "L3/value(" + (r.hasContent ? "data" : r.hasKjsPlugin ? "kjs" : "dist") + ")";
                } else if (requiredByKept) {
                    v = Verdict.KEEP;
                    src = "L3/required-by-kept";
                    d.keptByDep.add(fn);
                } else if (trusted) {
                    // P0-4：权威清单否决启发式隔离，但仍进报告供人工二次确认
                    v = Verdict.REPORT;
                    src = "L0/trusted-veto";
                    d.amber.add(fn + " [权威清单否决隔离]");
                } else if (cfg.pruneHarmlessClientMods && cfg.autoQuarantine) {
                    v = Verdict.QUARANTINE;
                    src = "L2/amber+prune";
                } else {
                    // v12 P0-3 核心转向：证据不足 → AMBER = 保留 + 观察 + 报告。
                    // 判「会不会崩服」而非「是不是客户端模组」——留一个不崩服的客户端模组代价是几 MB 内存，
                    // 误删一个库的代价是整包起不来。历史 5 次误删全部源于此处旧的有罪推定。
                    v = Verdict.REPORT;
                    src = "L2/amber";
                    d.amber.add(fn);
                }
            } else {
                v = Verdict.KEEP;
                src = "L3/no-harm";
            }
            sig(fn, id, m, r, trusted, v.name(), src);

            switch (v) {
                case QUARANTINE:
                    d.toQuarantine.add(jar);
                    // 黑名单与「自声明 CLIENT」同属硬证据，不允许被依赖闭包回补
                    if ("L0/blacklist".equals(src) || "L1/declared-client".equals(src)) {
                        d.hardQuarantined.add(jar);
                    }
                    break;
                case REPORT:
                    d.reported.add(fn);
                    d.keepCount++;
                    break;
                case KEEP:
                default:
                    d.keepCount++;
                    break;
            }
        }

        // v7 依赖一致性闭包
        Map<String, Path> quarantinedIds = new HashMap<String, Path>();
        for (Path q : d.toQuarantine) {
            ModMeta qm = meta.get(q);
            if (qm != null) quarantinedIds.put(qm.modId, q);
        }
        boolean changed = true;
        int rounds = 0;
        while (changed && rounds++ < 16) {
            changed = false;
            for (Map.Entry<Path, ModMeta> e : meta.entrySet()) {
                Path jar = e.getKey();
                ModMeta m = e.getValue();
                if (d.toQuarantine.contains(jar)) continue;
                for (String dep : m.dependencies) {
                    Path depJar = quarantinedIds.get(dep);
                    if (depJar == null) continue;
                    ScanResult r = result.get(jar);
                    boolean strong = CORE_MODIDS.contains(m.modId) || BUILTIN_SAFE.contains(m.modId)
                        || cfg.whitelist.contains(m.modId)
                        || "SERVER".equalsIgnoreCase(m.environment) || "BOTH".equalsIgnoreCase(m.environment)
                        || (r != null && (r.hasServer || r.hasContent || r.hasDistGuard || r.hasKjsPlugin));

                    // v12 关键修复：硬证据（类名指纹 / 黑名单 / mods.toml 自声明 CLIENT）不得被依赖闭包回补。
                    // 旧逻辑下只要有任一"强"模组声明依赖它，已确证会崩服的模组就会被放回 mods/——
                    // 实测 MaFgLib(确证崩服) / fancymenu / konkrete / mekalus(oculus 簇) 四个全部由此漏网。
                    // 缺依赖顶多让依赖方功能异常或自行报错，把崩服模组放回去则是整包起不来。
                    if (d.hardQuarantined.contains(depJar)) {
                        d.brokenDeps.add(m.modId + " 依赖已隔离的 " + dep
                            + "（" + depJar.getFileName() + "，硬证据不予回补）");
                        continue; // 不改变任何集合，也不置 changed，避免死循环
                    }

                    if (strong) {
                        d.toQuarantine.remove(depJar);
                        quarantinedIds.remove(dep);
                        d.keptByDep.add(depJar.getFileName() + " [被 " + m.modId + " 强依赖，回补保留]");
                        d.keepCount++;
                    } else {
                        d.toQuarantine.add(jar);
                        quarantinedIds.put(m.modId, jar);
                        d.chained.add(jar.getFileName() + " [强依赖已隔离的 " + dep + "，连坐隔离]");
                        d.keepCount--;
                    }
                    changed = true;
                    break;
                }
            }
        }
        return d;
    }

    public static final class Decision {
        final List<Path> toQuarantine = new ArrayList<Path>();
        final List<String> keptByDep = new ArrayList<String>();
        final List<String> reported = new ArrayList<String>();
        final List<String> byFingerprint = new ArrayList<String>();
        final List<String> chained = new ArrayList<String>();
        final List<String> restored = new ArrayList<String>(); // v10: 用户加回、本次跳过预隔离的模组
        // v12: AMBER = 含客户端代码但无确证危害证据，默认保留 + 列入观察名单
        final List<String> amber = new ArrayList<String>();
        // v12: 命中权威清单却仍被硬证据判为隔离——说明该参考清单自身混有客户端模组，需人工复核
        final List<String> trustedConflict = new ArrayList<String>();
        // v12: 硬证据隔离集（指纹 / 黑名单 / 自声明 CLIENT）。这些【不允许】被依赖闭包回补。
        final Set<Path> hardQuarantined = new HashSet<Path>();
        // v12: 因硬证据不回补而产生的依赖断链，仅告警，供人工决定是否找替代版本
        final Set<String> brokenDeps = new LinkedHashSet<String>();
        int keepCount;
    }

    /** v12 P0-7：把单个模组的全部信号与判定来源写入 _guard_precheck.log，使每一次隔离/保留都可审计、可复盘。 */
    private static void sig(String fn, String id, ModMeta m, ScanResult r, boolean trusted,
                            String verdict, String src) {
        note("  SIG " + fn + " id=" + id
            + " env=" + (m == null || m.environment == null ? "-" : m.environment)
            + " client=" + bit(r != null && r.hasClient)
            + " server=" + bit(r != null && r.hasServer)
            + " content=" + bit(r != null && r.hasContent)
            + " mixin=" + bit(r != null && r.hasCommonMixin)
            + " dist=" + bit(r != null && r.hasDistGuard)
            + " kjs=" + bit(r != null && r.hasKjsPlugin)
            + " trusted=" + bit(trusted)
            + " -> " + verdict + " [" + src + "]");
    }

    private static String bit(boolean v) {
        return v ? "1" : "0";
    }

    /** 类名指纹匹配（加速提示）。 */
    static String matchFingerprint(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (Map.Entry<String, String> e : KNOWN_BAD_FINGERPRINTS.entrySet()) {
                if (jf.getJarEntry(e.getKey()) != null) return e.getValue();
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static boolean isRequiredByKept(String targetId, Map<String, Set<String>> deps,
                                            Map<Path, ModMeta> meta, Map<Path, ScanResult> result, Config cfg) {
        for (Map.Entry<Path, ModMeta> e : meta.entrySet()) {
            ModMeta m = e.getValue();
            if (m.modId.equals(targetId)) continue;
            if (!deps.getOrDefault(m.modId, Collections.<String>emptySet()).contains(targetId)) continue;
            ScanResult r = result.get(e.getKey());
            boolean inWhite = cfg.whitelist.contains(m.modId);
            boolean safe = BUILTIN_SAFE.contains(m.modId) || inWhite
                || "SERVER".equalsIgnoreCase(m.environment) || "BOTH".equalsIgnoreCase(m.environment);
            if (safe || (r != null && (r.hasServer || r.hasContent || r.hasDistGuard || r.hasKjsPlugin))) return true;
        }
        return false;
    }

    private static void collectJars(Path dir, final List<Path> out) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()
                    && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * v12 P0-6：原子隔离。
     *
     * 旧实现用 Files.move(REPLACE_EXISTING)，在 Windows 上若源文件被占用会「目标已生成、源未删除」，
     * 造成同一 jar 同时存在于 mods/ 与 _quarantine/（实测发现 3 个：CutThrough / gtmoldraw / UniLib，
     * sha1 完全相同）——隔离形同虚设，模组仍被加载。
     *
     * 新实现：优先 ATOMIC_MOVE；不支持或失败则回退「复制 → 校验哈希 → 删源 → 确认源已消失」，
     * 且删源失败时【必须回滚删掉已复制的目标】，绝不留下半成品。
     */
    private static void quarantine(Path jar) throws IOException {
        Path rel = MODS_DIR.relativize(jar);
        Path target = QUARANTINE_DIR.resolve(rel);
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);
        try {
            Files.move(jar, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException atomicFailed) {
            // AtomicMoveNotSupportedException（跨卷）或占用失败，走校验式回退
            note("  QUARANTINE_FALLBACK " + jar.getFileName() + " (" + atomicFailed.getClass().getSimpleName() + ")");
        }

        String srcHash = sha1(jar);
        Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
        String dstHash = sha1(target);
        if (srcHash.isEmpty() || !srcHash.equals(dstHash)) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw new IOException("隔离复制校验失败(哈希不一致): " + jar.getFileName());
        }
        try {
            Files.delete(jar);
        } catch (IOException delFailed) {
            // 关键：删源失败必须回滚，否则就复现了 mods/ 与 _quarantine/ 同时存在的 bug
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw delFailed;
        }
        if (Files.exists(jar)) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw new IOException("隔离后源文件仍存在: " + jar.getFileName());
        }
    }

    /**
     * v12 P0-6b：启动时校验 mods/ 与隔离区的重复残留。
     * 只比对【同名】文件（真实 bug 的形态），同名再比 sha1：
     *   - 哈希相同  → mods/ 侧是隔离失败的残留，直接删除（隔离区已有完整副本，不丢文件）；
     *   - 哈希不同  → 多为版本升级后重名，只告警不动手，交人工判断。
     * 只比同名可把开销压到近乎为零，避免为此全量哈希 280+ 个 jar。
     */
    private static void reconcileQuarantineDuplicates(List<Path> jars) {
        if (!Files.isDirectory(QUARANTINE_DIR)) return;
        Map<String, Path> quarantined = new HashMap<String, Path>();
        try {
            List<Path> qJars = new ArrayList<Path>();
            collectJars(QUARANTINE_DIR, qJars);
            for (Path q : qJars) quarantined.put(q.getFileName().toString().toLowerCase(Locale.ROOT), q);
        } catch (IOException e) {
            return;
        }
        if (quarantined.isEmpty()) return;

        int removed = 0;
        for (Iterator<Path> it = jars.iterator(); it.hasNext(); ) {
            Path live = it.next();
            Path dup = quarantined.get(live.getFileName().toString().toLowerCase(Locale.ROOT));
            if (dup == null) continue;
            String h1 = sha1(live);
            String h2 = sha1(dup);
            if (!h1.isEmpty() && h1.equals(h2)) {
                try {
                    Files.delete(live);
                    it.remove();
                    removed++;
                    System.out.println("[PRTS] 客户端模组预检: 清除隔离残留（mods/ 与隔离区同文件）-> " + live.getFileName());
                    note("  DUP_RESIDUE_REMOVED " + live.getFileName() + " sha1=" + h1);
                } catch (IOException e) {
                    note("  DUP_RESIDUE_REMOVE_FAIL " + live.getFileName() + " " + e);
                }
            } else {
                System.out.println("[PRTS] 客户端模组预检: 注意——" + live.getFileName()
                    + " 在 mods/ 与隔离区同名但内容不同（疑似版本升级），保持原样，请人工确认");
                note("  DUP_NAME_DIFF " + live.getFileName());
            }
        }
        if (removed > 0) {
            System.out.println("[PRTS] 客户端模组预检: 共清除 " + removed + " 个隔离残留（此前隔离未生效，模组仍在被加载）");
        }
    }

    /**
     * 运行时隔离：优先移动到隔离区（同卷 rename，通常可行）；Windows 占用失败则同目录改名兜底。
     * v12：捕获范围放宽到 IOException——新的原子隔离在校验失败时会抛普通 IOException，
     * 此时同样应走改名兜底，而不是让异常冒泡打断自愈流程。
     */
    private static void runtimeQuarantine(Path jar) throws IOException {
        try {
            quarantine(jar);
        } catch (IOException fse) {
            Path renamed = jar.resolveSibling(jar.getFileName().toString() + PENDING_SUFFIX);
            Files.move(jar, renamed, StandardCopyOption.REPLACE_EXISTING);
            note("  RUNTIME_QUARANTINE_RENAME " + renamed.getFileName());
        }
    }

    static ScanResult scanJarFull(Path jar) {
        ScanResult r = new ScanResult();
        List<String> commonMixinClasses = new ArrayList<String>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String rawName = e.getName();
                String name = rawName.toLowerCase();
                if (name.endsWith(".class") && (name.contains("/fabric/") || name.contains("_fabric/"))) continue;
                // 双端信号(按条目名判定，无需读字节)
                if (!r.hasContent) {
                    // data/<ns>/(recipes|loot_tables|tags|worldgen|advancements)/ -> 内容模组(双端，如 barrels_2012)
                    if (name.matches("data/[^/]+/(recipes|loot_tables|tags|worldgen|advancements)/.+")) r.hasContent = true;
                }
                if (!r.hasKjsPlugin && (name.equals("kubejs.plugins.txt") || name.equals("kubejs.classfilter.txt"))) {
                    r.hasKjsPlugin = true;
                }
                boolean skipClientSignal = name.endsWith(".class") && name.contains("/config/");
                boolean scan = name.endsWith(".class") || name.endsWith(".json")
                    || name.endsWith(".mixin.json") || name.endsWith(".toml") || name.endsWith(".cfg");
                if (!scan) continue;
                if (e.getSize() > 2L * 1024 * 1024) continue;
                try (InputStream is = jf.getInputStream(e)) {
                    byte[] b = readAll(is);
                    checkBytes(b, r, skipClientSignal);
                    if (name.endsWith(".json") && name.contains("mixin")) {
                        String s = new String(b, StandardCharsets.ISO_8859_1);
                        collectCommonMixinClasses(s, commonMixinClasses);
                    }
                } catch (IOException ex) { note("SCAN_ENTRY_FAIL " + e.getName() + " " + ex); }
                if (r.hasClient && r.hasServer && r.hasContent) break;
            }
            boolean anyClean = false;
            boolean anyPoison = false;
            for (String cp : commonMixinClasses) {
                JarEntry me = jf.getJarEntry(cp);
                if (me == null) continue;
                try (InputStream is = jf.getInputStream(me)) {
                    byte[] b = readAll(is);
                    boolean cli = false;
                    for (String m : CLIENT_MARKERS) if (contains(b, m)) { cli = true; break; }
                    if (cli) anyPoison = true; else anyClean = true;
                } catch (IOException ignored) {}
            }
            if (anyClean && !anyPoison) r.hasCommonMixin = true;
        } catch (IOException ignored) {}
        return r;
    }

    static void collectCommonMixinClasses(String json, List<String> out) {
        String pkg = jsonString(json, "package");
        int i = json.indexOf("\"mixins\"");
        if (i < 0) return;
        int lb = json.indexOf('[', i);
        if (lb < 0) return;
        int rb = json.indexOf(']', lb);
        if (rb < 0) return;
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(json.substring(lb + 1, rb));
        while (m.find()) {
            String cls = m.group(1).replace('.', '/');
            out.add((pkg != null ? pkg.replace('.', '/') + "/" : "") + cls + ".class");
        }
    }

    static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static boolean hasNonEmptyJsonArray(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return false;
        int lb = json.indexOf('[', i);
        if (lb < 0) return false;
        int rb = json.indexOf(']', lb);
        if (rb < 0) return false;
        return json.substring(lb + 1, rb).trim().length() > 0;
    }

    private static void checkBytes(byte[] b, ScanResult r, boolean skipClientSignal) {
        if (!r.hasClient && !skipClientSignal) {
            for (String m : CLIENT_MARKERS) if (contains(b, m)) { r.hasClient = true; break; }
        }
        if (!r.hasServer) {
            for (String m : SERVER_MARKERS) if (contains(b, m)) { r.hasServer = true; break; }
        }
        if (!r.hasContent) {
            for (String m : CONTENT_MARKERS) if (contains(b, m)) { r.hasContent = true; break; }
        }
        if (!r.hasDistGuard) {
            for (String m : DIST_GUARD_MARKERS) if (contains(b, m)) { r.hasDistGuard = true; break; }
        }
    }

    private static boolean contains(byte[] b, String s) {
        byte[] pat = s.getBytes(StandardCharsets.UTF_8);
        if (pat.length == 0 || pat.length > b.length) return false;
        for (int i = 0; i + pat.length <= b.length; i++) {
            boolean ok = true;
            for (int j = 0; j < pat.length; j++) {
                if (b[i + j] != pat[j]) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
    }

    /**
     * 扫描结果缓存键：算法版本 + 文件名 + 文件大小 + 最后修改时间（后三者唯一标识 jar 内容，任一变化即失效）。
     * v12: 前缀算法版本号——DIST_GUARD_MARKERS 语义变更后，旧缓存里的 hasDistGuard 值不再可信，
     * 必须整体作废重扫，否则升级后首启会继续沿用错误信号。今后每次改扫描判据都要 bump 此常量。
     */
    private static final String SCAN_ALGO_VERSION = "v12";

    private static String cacheKey(Path jar) {
        try {
            return SCAN_ALGO_VERSION + "|" + jar.getFileName() + ":" + Files.size(jar)
                + ":" + Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException e) {
            return SCAN_ALGO_VERSION + "|" + jar.getFileName(); // 取不到元信息则退化（每轮重扫）
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    static ModMeta detectModMeta(Path jar) {
        ModMeta meta = new ModMeta();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (String name : new String[]{"META-INF/mods.toml", "META-INF/neoforge.mods.toml"}) {
                JarEntry je = jf.getJarEntry(name);
                if (je == null) continue;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(jf.getInputStream(je), StandardCharsets.UTF_8))) {
                    String line;
                    boolean inDeps = false;
                    String curModId = null;
                    String depId = null;
                    boolean depMandatory = true;
                    boolean depClientSide = false;
                    while ((line = br.readLine()) != null) {
                        String t = line.trim();
                        boolean newSection = t.startsWith("[");
                        if (newSection) {
                            if (inDeps && depId != null && depMandatory && !depClientSide) {
                                meta.dependencies.add(depId);
                            }
                            depId = null; depMandatory = true; depClientSide = false;
                            inDeps = t.startsWith("[[dependencies.");
                            continue;
                        }
                        int mid = t.indexOf("modId");
                        if (mid >= 0 && t.contains("=")) {
                            int eq = t.indexOf('=');
                            String raw = t.substring(eq + 1);
                            int hash = raw.indexOf('#');
                            if (hash >= 0) raw = raw.substring(0, hash);
                            String id = raw.trim().replace("\"", "").trim();
                            if (!id.isEmpty() && !id.startsWith("[")) {
                                if (inDeps) depId = id.toLowerCase();
                                else if (curModId == null) curModId = id.toLowerCase();
                            }
                        }
                        if (inDeps) {
                            String v = tomlValue(t, "mandatory");
                            if (v != null) depMandatory = "true".equalsIgnoreCase(v);
                            String sv = tomlValue(t, "side");
                            if (sv != null) depClientSide = "CLIENT".equalsIgnoreCase(sv);
                        }
                        int eid = t.indexOf("environment");
                        if (eid >= 0 && t.contains("=")) {
                            int eq = t.indexOf('=');
                            String rawEnv = t.substring(eq + 1);
                            int eh = rawEnv.indexOf('#');
                            if (eh >= 0) rawEnv = rawEnv.substring(0, eh);
                            String env = rawEnv.trim().replace("\"", "").trim();
                            if (!env.isEmpty()) meta.environment = env.toUpperCase();
                        }
                    }
                    if (inDeps && depId != null && depMandatory && !depClientSide) {
                        meta.dependencies.add(depId);
                    }
                    if (curModId != null && meta.modId == null) meta.modId = curModId;
                }
            }
            // A1: 通用解析 Fabric 客户端声明（fabric.mod.json 的 environment/side），零误杀。
            JarEntry fj = jf.getJarEntry("fabric.mod.json");
            if (fj != null) {
                try (InputStream fis = jf.getInputStream(fj)) {
                    String fs = new String(readAll(fis), StandardCharsets.UTF_8);
                    String env = jsonString(fs, "environment");
                    if (env == null) env = jsonString(fs, "side"); // 兼容旧字段
                    if (env != null) {
                        env = env.toUpperCase();
                        if (meta.environment == null || "BOTH".equals(meta.environment))
                            meta.environment = env; // CLIENT 优先覆盖
                    }
                    if (meta.modId == null) {
                        String fid = jsonString(fs, "id");
                        if (fid != null) meta.modId = fid.toLowerCase();
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException e) { note("DETECT_META_FAIL " + jar.getFileName() + " " + e); }
        if (meta.modId == null) {
            String fn = jar.getFileName().toString().toLowerCase();
            if (fn.endsWith(".jar")) fn = fn.substring(0, fn.length() - 4);
            meta.modId = fn;
        }
        return meta;
    }

    static final class ScanResult {
        boolean hasClient;
        boolean hasServer;
        boolean hasContent;
        boolean hasCommonMixin;
        boolean hasDistGuard;   // 双端守卫(Dist/EnvType 自检)：安全的客户端模组
        boolean hasKjsPlugin;   // KubeJS 插件(kubejs.plugins.txt)：双端 KubeJS 附属
    }

    private static String tomlValue(String trimmedLine, String key) {
        if (!trimmedLine.startsWith(key)) return null;
        String rest = trimmedLine.substring(key.length()).trim();
        if (!rest.startsWith("=")) return null;
        String raw = rest.substring(1);
        int hash = raw.indexOf('#');
        if (hash >= 0) raw = raw.substring(0, hash);
        return raw.trim().replace("\"", "").trim();
    }

    static final class ModMeta {
        String modId;
        String environment;
        final Set<String> dependencies = new HashSet<String>();
    }

    private enum Verdict { KEEP, QUARANTINE, REPORT }

    // ===================== 运行时自愈 =====================

    /** 是否为"缺失客户端类"导致的崩溃（strict：仅客户端类前缀才算，避免吞掉无关崩溃）。 */
    static boolean isClientClassMissing(Throwable t) {
        Throwable c = t;
        while (c != null) {
            String msg = c.getMessage();
            if (msg != null && containsClientPrefix(msg)) return true;
            c = c.getCause();
        }
        return false;
    }

    private static boolean containsClientPrefix(String msg) {
        for (String p : CLIENT_CLASS_PREFIX_DOT) if (msg.contains(p)) return true;
        for (String p : CLIENT_CLASS_PREFIX_SLASH) if (msg.contains(p)) return true;
        return false;
    }

    /** 从异常链里提取缺失的客户端类名（用于日志/状态记录）。 */
    private static String missingClassName(Throwable t) {
        Throwable c = t;
        while (c != null) {
            String msg = c.getMessage();
            if (msg != null) {
                for (String p : CLIENT_CLASS_PREFIX_DOT) {
                    int i = msg.indexOf(p);
                    if (i >= 0) return msg.substring(i);
                }
                for (String p : CLIENT_CLASS_PREFIX_SLASH) {
                    int i = msg.indexOf(p);
                    if (i >= 0) return msg.substring(i);
                }
            }
            c = c.getCause();
        }
        return "unknown-client-class";
    }

    private static String summarize(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int n = 0;
        while (c != null && n++ < 4) {
            sb.append(c.getClass().getName());
            if (c.getMessage() != null) sb.append(":").append(c.getMessage());
            sb.append(" | ");
            c = c.getCause();
        }
        return sb.toString();
    }

    /** 从异常栈里定位 offending mod：找最深的非核心（模组自身）类，并查出它在哪个 mods jar 里。 */
    static Path locateOffendingMod(Throwable t) {
        Path dir = MODS_DIR != null && Files.isDirectory(MODS_DIR)
            ? MODS_DIR : Paths.get(System.getProperty("fml.modsDir", "mods"));
        List<Path> jars = new ArrayList<Path>();
        try { collectJars(dir, jars); } catch (IOException ignored) {}
        Throwable c = t;
        int frames = 0;
        while (c != null && frames < 64) {
            for (StackTraceElement ste : c.getStackTrace()) {
                frames++;
                String cn = ste.getClassName();
                if (isCoreClass(cn)) continue;
                String path = cn.replace('.', '/') + ".class";
                Path jar = findJarContainingClass(jars, path);
                if (jar != null) return jar;
            }
            c = c.getCause();
        }
        return null;
    }

    private static boolean isCoreClass(String cn) {
        for (String p : CORE_CLASS_PREFIX) if (cn.startsWith(p)) return true;
        return false;
    }

    private static Path findJarContainingClass(List<Path> jars, String classPath) {
        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                if (jf.getJarEntry(classPath) != null) return jar;
            } catch (IOException ignored) {}
        }
        return null;
    }

    private static void restart(String[] args) {
        int retry = currentRetry() + 1;
        if (retry > MAX_RESTART) {
            System.err.println("[PRTS] 自愈重启次数已达上限(" + MAX_RESTART + ")，停止自动重启。请检查并清理 mods/ 中的客户端模组。");
            System.exit(1);
            return;
        }
        List<String> cmd = buildCommand(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        pb.environment().put(RETRY_ENV, String.valueOf(retry));
        try {
            pb.start();
            System.exit(0);
        } catch (IOException e) {
            System.err.println("[PRTS] 自愈重启失败（无法启动新 JVM）: " + e);
            System.exit(1);
        }
    }

    private static int currentRetry() {
        String v = System.getenv(RETRY_ENV);
        if (v == null) return 0;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; }
    }

    /** 重建启动命令：复用原 JVM 参数（含 -Xmx 等）与 jar 路径，保证重启与首次一致。
     *  返回 List<String> 供 ProcessBuilder 直接使用（避免字符串拼接的引号转义/空格路径陷阱）。
     *  兼容 JAVA_HOME 含空格（Windows 常见 C:\Program Files\Java\...）、-D 参数值含空格、自定义 ClassLoader 等边缘场景。 */
    private static List<String> buildCommand(String[] args) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaExe());
        RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
        List<String> inputArgs = bean.getInputArguments();
        boolean hasJar = false;
        for (String a : inputArgs) {
            if ("-jar".equals(a)) hasJar = true;
            cmd.add(a);
        }
        if (!hasJar) {
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add("io.izzel.arclight.server.Launcher");
        }
        if (args != null) {
            for (String a : args) cmd.add(a);
        }
        return cmd;
    }

    private static String javaExe() {
        String home = System.getProperty("java.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // File.getCanonicalPath 规范化路径（消除 ../ / ./ 及 OS 特定分隔符），
        // 确保 ProcessBuilder 在任何 JAVA_HOME 路径（含空格、符号链接、混合分隔符）下都能正确定位 java 可执行文件。
        try {
            home = new File(home).getCanonicalPath();
        } catch (IOException ignored) {}
        return os.contains("win")
            ? home + File.separator + "bin" + File.separator + "java.exe"
            : home + File.separator + "bin" + File.separator + "java";
    }

    private static String sha1(Path p) {
        try (InputStream is = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ===================== 配置 / 状态 =====================

    public static final class Config {
        final Set<String> whitelist = new HashSet<String>();
        final Set<String> blacklist = new HashSet<String>();
        boolean autoQuarantine = true;

        /**
         * v12 P0-5：AMBER（有客户端代码、但无任何确证危害证据）是否也隔离。
         * 默认 false —— 无罪推定。一个不崩服的客户端模组留在服务端，代价是几 MB 内存；
         * 误删一个库的代价是整包起不来。历史 5 次误删全部源于「证据不足即隔离」。
         * 想要「清干净」的管理员可显式开启。
         */
        boolean pruneHarmlessClientMods = false;

        /**
         * v12 P0-4：权威参考清单。可填目录（取其中所有 jar 的文件名与 modId）或文本文件（每行一个 modId/文件名）。
         * 语义 = 【否决启发式隔离，但不跳过判定】：
         *   - 命中者不会因 AMBER/启发式判据被自动隔离；
         *   - 仍然完整跑判定并写入审计日志；
         *   - 【不否决】黑名单、类名指纹、mods.toml 显式 CLIENT 声明这三类硬证据——
         *     实测参考集 D:/mc/PRTS/1/mods 自身就混有至少 4 个纯客户端模组，
         *     若无条件放行等于把人工失误固化进系统。此时会打印 TRUSTED_CONFLICT 告警。
         */
        final Set<String> trustedIds = new HashSet<String>();
        final Set<String> trustedNames = new HashSet<String>();
        final List<String> trustedSources = new ArrayList<String>();

        boolean isTrusted(String modId, String fileName) {
            if (trustedIds.isEmpty() && trustedNames.isEmpty()) return false;
            if (modId != null && trustedIds.contains(modId.toLowerCase(Locale.ROOT))) return true;
            return fileName != null && trustedNames.contains(fileName.toLowerCase(Locale.ROOT));
        }

        static Config load() {
            Config c = new Config();
            Path p = Paths.get("clientside-guard.json");
            if (Files.exists(p)) {
                try {
                    String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    c.whitelist.addAll(parseArray(json, "whitelist"));
                    c.whitelist.addAll(parseArray(json, "allowlist")); // v10: allowlist 同义逃生口
                    c.blacklist.addAll(parseArray(json, "blacklist"));
                    Matcher m = Pattern.compile("\"autoQuarantine\"\\s*:\\s*(true|false)").matcher(json);
                    if (m.find()) c.autoQuarantine = Boolean.parseBoolean(m.group(1));
                    Matcher pm = Pattern.compile("\"pruneHarmlessClientMods\"\\s*:\\s*(true|false)").matcher(json);
                    if (pm.find()) c.pruneHarmlessClientMods = Boolean.parseBoolean(pm.group(1));
                    for (String src : parseArrayRaw(json, "trustedModList")) c.loadTrusted(src);
                } catch (IOException ignored) {}
            }
            // v10: 兼容 prts.yml guard.allowlist（尽力解析，失败忽略）
            c.whitelist.addAll(readPrtsAllowlist());
            return c;
        }

        /** 载入一个权威清单来源：目录（扫 *.jar 取文件名+modId）或文本文件（每行一项，# 开头为注释）。 */
        private void loadTrusted(String src) {
            if (src == null || src.trim().isEmpty()) return;
            String s = src.trim();
            int n = 0;
            try {
                Path p = Paths.get(s);
                if (!Files.exists(p)) {
                    trustedSources.add(s + " [路径不存在，已忽略]");
                    return;
                }
                if (Files.isDirectory(p)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.jar")) {
                        for (Path jar : ds) {
                            trustedNames.add(jar.getFileName().toString().toLowerCase(Locale.ROOT));
                            String id = detectModMeta(jar).modId;
                            if (id != null && !id.isEmpty()) trustedIds.add(id.toLowerCase(Locale.ROOT));
                            n++;
                        }
                    }
                } else {
                    for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) continue;
                        String lower = t.toLowerCase(Locale.ROOT);
                        if (lower.endsWith(".jar")) trustedNames.add(lower);
                        else trustedIds.add(lower);
                        n++;
                    }
                }
                trustedSources.add(s + " [" + n + " 项]");
            } catch (Exception e) {
                // 权威清单是【锦上添花】的兜底，读取失败绝不能影响启动
                trustedSources.add(s + " [读取失败: " + e.getClass().getSimpleName() + "，已忽略]");
            }
        }

        /** 与 parseArray 相同，但保留原始大小写（路径不可小写化）。 */
        private static List<String> parseArrayRaw(String json, String key) {
            List<String> list = new ArrayList<String>();
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
            if (m.find()) {
                Matcher sm = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
                while (sm.find()) list.add(sm.group(1));
            }
            return list;
        }

        private static Set<String> parseArray(String json, String key) {
            Set<String> set = new HashSet<String>();
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
            if (m.find()) {
                Matcher sm = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
                while (sm.find()) set.add(sm.group(1).toLowerCase());
            }
            return set;
        }

        /** 尽力从 prts.yml 解析 guard.allowlist（容忍缩进/顺序/注释/多行数组，解析失败返回空集并记录）。 */
        private static Set<String> readPrtsAllowlist() {
            Set<String> set = new HashSet<String>();
            Path p = Paths.get("prts.yml");
            if (!Files.exists(p)) return set;
            try {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                boolean inGuard = false;
                boolean inAllow = false;
                int allowIndent = -1; // 记录 allowlist 行缩进基准（容忍不同缩进风格）
                for (String raw : lines) {
                    String line = raw.replace("\t", " ");
                    // 跳过空行与纯注释行
                    String trim = line.trim();
                    if (trim.isEmpty() || trim.startsWith("#")) continue;
                    int indent = 0;
                    while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                    if (!inGuard) {
                        if (trim.startsWith("guard:") || trim.equals("guard:")) inGuard = true;
                        continue;
                    }
                    if (trim.startsWith("allowlist:") || trim.equals("allowlist:")) {
                        inAllow = true;
                        allowIndent = indent; // 基准缩进
                        continue;
                    }
                    if (inAllow) {
                        // 允许同层或更深缩进的列表项；遇到同层或更浅的非列表项则退出
                        if (!trim.startsWith("-") && indent <= allowIndent) { inAllow = false; continue; }
                        if (trim.startsWith("- ")) {
                            String id = trim.substring(2).split("#")[0].trim().replace("\"", "").replace("'", "");
                            if (!id.isEmpty()) set.add(id.toLowerCase());
                        } else if (trim.startsWith("-")) {
                            String id = trim.substring(1).split("#")[0].trim().replace("\"", "").replace("'", "");
                            if (!id.isEmpty()) set.add(id.toLowerCase());
                        }
                        // 忽略非 "-" 开头的深层嵌套（如子对象），不退出
                    }
                    // 其他 guard 子段（如 blacklist / autoQuarantine）不处理，也不退出 guard
                }
            } catch (IOException e) {
                note("CONFIG_PARSE_FAIL prts.yml: " + e);
            }
            return set;
        }
    }

    /** v10: 跨启动状态记忆。quarantined=我们隔离过的 modId（用于识别"用户加回"）；insistedFailed=连崩计数。 */
    public static final class GuardState {
        final Map<String, Info> quarantined = new LinkedHashMap<String, Info>();
        final Map<String, Info> insistedFailed = new LinkedHashMap<String, Info>();
        final Map<String, ScanResult> scanCache = new LinkedHashMap<String, ScanResult>(); // v10c: 扫描结果缓存

        static GuardState load() {
            GuardState s = new GuardState();
            Path p = Paths.get("_guard_state.json");
            if (!Files.exists(p)) return s;
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                String qBlock = block(json, "quarantined");
                if (qBlock != null) {
                    Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{").matcher(qBlock);
                    while (m.find()) s.quarantined.put(m.group(1), new Info("", "", 0));
                }
                String iBlock = block(json, "insisted_failed");
                if (iBlock != null) {
                    Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{\\s*\"sha1\".*?\"fails\"\\s*:\\s*(\\d+)")
                        .matcher(iBlock);
                    while (m.find()) s.insistedFailed.put(m.group(1), new Info("", "", Long.parseLong(m.group(2))));
                }
                String scBlock = block(json, "scan_cache");
                if (scBlock != null) {
                    Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\\{\\s*\"hasClient\"\\s*:\\s*(true|false),\\s*\"hasServer\"\\s*:\\s*(true|false),\\s*\"hasContent\"\\s*:\\s*(true|false),\\s*\"hasCommonMixin\"\\s*:\\s*(true|false),\\s*\"hasDistGuard\"\\s*:\\s*(true|false),\\s*\"hasKjsPlugin\"\\s*:\\s*(true|false)")
                        .matcher(scBlock);
                    while (m.find()) {
                        ScanResult sr = new ScanResult();
                        sr.hasClient = Boolean.parseBoolean(m.group(2));
                        sr.hasServer = Boolean.parseBoolean(m.group(3));
                        sr.hasContent = Boolean.parseBoolean(m.group(4));
                        sr.hasCommonMixin = Boolean.parseBoolean(m.group(5));
                        sr.hasDistGuard = Boolean.parseBoolean(m.group(6));
                        sr.hasKjsPlugin = Boolean.parseBoolean(m.group(7));
                        s.scanCache.put(m.group(1), sr);
                    }
                }
            } catch (IOException ignored) {}
            return s;
        }

        void save() {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("{\n  \"version\": 3,\n  \"quarantined\": {\n");
                boolean first = true;
                for (Map.Entry<String, Info> e : quarantined.entrySet()) {
                    if (!first) sb.append(",\n");
                    first = false;
                    sb.append("    ").append(quote(e.getKey())).append(": ")
                        .append("{ \"sha1\": ").append(quote(e.getValue().sha1))
                        .append(", \"reason\": ").append(quote(e.getValue().reason))
                        .append(", \"at\": ").append(e.getValue().at).append(" }");
                }
                sb.append("\n  },\n  \"insisted_failed\": {\n");
                first = true;
                for (Map.Entry<String, Info> e : insistedFailed.entrySet()) {
                    if (!first) sb.append(",\n");
                    first = false;
                    sb.append("    ").append(quote(e.getKey())).append(": ")
                        .append("{ \"sha1\": ").append(quote(e.getValue().sha1))
                        .append(", \"reason\": ").append(quote(e.getValue().reason))
                        .append(", \"fails\": ").append(e.getValue().at).append(" }");
                }
                sb.append("\n  },\n  \"scan_cache\": {\n");
                first = true;
                for (Map.Entry<String, ScanResult> e : scanCache.entrySet()) {
                    if (!first) sb.append(",\n");
                    first = false;
                    ScanResult r = e.getValue();
                    sb.append("    ").append(quote(e.getKey())).append(": ")
                        .append("{ \"hasClient\": ").append(r.hasClient)
                        .append(", \"hasServer\": ").append(r.hasServer)
                        .append(", \"hasContent\": ").append(r.hasContent)
                        .append(", \"hasCommonMixin\": ").append(r.hasCommonMixin)
                        .append(", \"hasDistGuard\": ").append(r.hasDistGuard)
                        .append(", \"hasKjsPlugin\": ").append(r.hasKjsPlugin).append(" }");
                }
                sb.append("\n  }\n}\n");
                Files.write(Paths.get("_guard_state.json"), sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ignored) {}
        }

        static final class Info {
            final String sha1;
            final String reason;
            final long at; // quarantined: 时间戳; insistedFailed: 复用 at 字段存 fails 计数
            Info(String sha1, String reason, long at) {
                this.sha1 = sha1 == null ? "" : sha1;
                this.reason = reason == null ? "" : reason;
                this.at = at;
            }
        }

        private static String block(String json, String key) {
            int i = json.indexOf("\"" + key + "\"");
            if (i < 0) return null;
            int lb = json.indexOf('{', i);
            if (lb < 0) return null;
            int depth = 0;
            for (int j = lb; j < json.length(); j++) {
                char ch = json.charAt(j);
                if (ch == '{') depth++;
                else if (ch == '}') { depth--; if (depth == 0) return json.substring(lb, j + 1); }
            }
            return null;
        }

        private static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') sb.append('\\');
                sb.append(c);
            }
            sb.append("\"");
            return sb.toString();
        }
    }

    // ===================== 日志落盘 =====================

    private static void note(String s) {
        try {
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
            String line = "[" + ts + "] " + s + System.lineSeparator();
            Files.write(PRECHECK_LOG, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            // 自愈/崩溃事件额外写入持久日志（precheck log 每次启动会被清空重建）
            if (s.startsWith("SELFHEAL") || s.startsWith("SHUTDOWN_HEAL") || s.startsWith("CRASH")
                || s.startsWith("QUARANTINE_FAIL") || s.startsWith("CRASHREPORT")) {
                Files.write(Paths.get("_guard_heal.log"), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                // v10b: 自愈/崩溃事件额外桥接进 logs/ 目录（管理员常规查看 logs/latest.log 时也能发现）
                try {
                    Files.createDirectories(Paths.get("logs"));
                    Files.write(Paths.get("logs", "guard-heal.log"), line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored2) {}
            }
        } catch (IOException ignored) {}
    }
}
