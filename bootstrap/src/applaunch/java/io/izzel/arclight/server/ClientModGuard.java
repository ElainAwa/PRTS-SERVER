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
        "net/neoforged/neoforge/event/server",
        "com/mojang/brigadier",
        "com.mojang.brigadier"
    };

    // 内容注册标记
    private static final String[] CONTENT_MARKERS = {
        "DeferredRegister",
        "RegisterEvent",
        "RegistryEvent"
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
        KNOWN_BAD_FINGERPRINTS.put("org/thinkingstudio/mafglib/MaFgLib.class", "纯客户端(MaFgLib,基于 masa malilib 客户端库,构造加载 Screen 崩服)");
        KNOWN_BAD_FINGERPRINTS.put("org/thinkingstudio/tweakerge/Tweakerge.class", "纯客户端(Tweakerge,基于 masa tweakeroo 客户端库,构造加载 Screen 崩服)");
        KNOWN_BAD_FINGERPRINTS.put("com/github/leawind/thirdperson/ThirdPerson.class", "纯客户端(Leawind第三人称,摄像机模组)");
        KNOWN_BAD_FINGERPRINTS.put("dzwdz/chat_heads/ChatHeads.class", "纯客户端(聊天头像)");
        KNOWN_BAD_FINGERPRINTS.put("squeek/appleskin/ModConfig.class", "纯客户端(苹果皮,饥饿/食物 HUD)");
        KNOWN_BAD_FINGERPRINTS.put("me/jellysquid/mods/sodium/client/SodiumClientMod.class", "纯客户端(Sodium系旧包名:Embeddium/Rubidium<0.8)");
        KNOWN_BAD_FINGERPRINTS.put("net/caffeinemc/mods/sodium/service/SodiumServiceModLocator.class", "纯客户端(Sodium 0.8+新包名 net.caffeinemc)");
        KNOWN_BAD_FINGERPRINTS.put("net/caffeinemc/mods/sodium/desktop/LaunchWarn.class", "纯客户端(Sodium 0.8+桌面启动警告类)");
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
        "ae2ct"
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
    public static void handleCrash(Throwable t, String[] args) {
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
        } catch (Throwable ignored) {}
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
        note("START modsDir=" + MODS_DIR.toAbsolutePath() + " jars=" + jars.size());

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
        if (!d.restored.isEmpty()) {
            System.out.println("[PRTS] 曾被隔离、已被用户加回的模组（尊重用户选择，跳过预隔离）: " + String.join(", ", d.restored));
            for (String s : d.restored) note("  RESTORED " + s);
        }
        if (!d.reported.isEmpty()) {
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
            + " reported=" + d.reported.size()
            + (moved ? " (moved)" : (d.toQuarantine.isEmpty() && d.reported.isEmpty() ? " (none)" : " (report-only)")));
        for (String s : d.byFingerprint) note("  FINGERPRINT " + s);
        for (Path p : d.toQuarantine) note("  QUARANTINE " + p.getFileName());
        for (String s : d.chained) note("  CHAINED " + s);
        for (String s : d.keptByDep) note("  KEPT_BY_DEP " + s);
        if (moved) state.save();
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
        for (Path jar : jars) {
            String fileName = jar.getFileName().toString();
            if (!fileName.toLowerCase().endsWith(".jar")) continue;
            meta.put(jar, detectModMeta(jar));
            result.put(jar, scanJarFull(jar));
            done++;
            if (done % 50 == 0) {
                long el = System.currentTimeMillis() - t0;
                System.out.println("[PRTS] 客户端模组预检: 已扫描 " + done + "/" + total + "（" + (el / 1000) + "s）...");
            }
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
            if (CORE_MODIDS.contains(m.modId)) { d.keepCount++; continue; }

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
                continue;
            }

            boolean envClient = "CLIENT".equalsIgnoreCase(m.environment);
            boolean envServer = "SERVER".equalsIgnoreCase(m.environment) || "BOTH".equalsIgnoreCase(m.environment);
            boolean suspect = r.hasClient && !r.hasServer && !r.hasContent && !r.hasCommonMixin;

            String fpReason = matchFingerprint(jar);
            if (fpReason != null && !inWhite) {
                d.toQuarantine.add(jar);
                d.byFingerprint.add(fn + " [" + fpReason + "]");
                continue;
            }

            Verdict v;
            if (inBlack) {
                v = Verdict.QUARANTINE;
            } else if (inWhite || BUILTIN_SAFE.contains(id) || envServer) {
                v = Verdict.KEEP;
            } else if (envClient) {
                v = cfg.autoQuarantine ? Verdict.QUARANTINE : Verdict.REPORT;
            } else if (suspect) {
                boolean requiredByKept = isRequiredByKept(id, deps, meta, result, cfg);
                if (requiredByKept) {
                    v = Verdict.KEEP;
                    d.keptByDep.add(fn);
                } else if (cfg.autoQuarantine) {
                    v = Verdict.QUARANTINE;
                } else {
                    v = Verdict.REPORT;
                }
            } else {
                v = Verdict.KEEP;
            }

            switch (v) {
                case QUARANTINE:
                    d.toQuarantine.add(jar);
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
                        || (r != null && (r.hasServer || r.hasContent));
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
        int keepCount;
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
            if (safe || (r != null && (r.hasServer || r.hasContent))) return true;
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

    private static void quarantine(Path jar) throws IOException {
        Path rel = MODS_DIR.relativize(jar);
        Path target = QUARANTINE_DIR.resolve(rel);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) Files.delete(target);
        Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 运行时隔离：优先移动到隔离区（同卷 rename，通常可行）；Windows 占用失败则同目录改名兜底。 */
    private static void runtimeQuarantine(Path jar) throws IOException {
        try {
            quarantine(jar);
        } catch (FileSystemException fse) {
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
                } catch (IOException ignored) {}
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
        } catch (IOException ignored) {}
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

    /** 重建启动命令：复用原 JVM 参数（含 -Xmx 等）与 jar 路径，保证重启与首次一致。 */
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
        return home + (os.contains("win") ? "\\bin\\java.exe" : "/bin/java");
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
                } catch (IOException ignored) {}
            }
            // v10: 兼容 prts.yml guard.allowlist（尽力解析，失败忽略）
            c.whitelist.addAll(readPrtsAllowlist());
            return c;
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

        /** 尽力从 prts.yml 解析 guard.allowlist（容忍缩进/顺序，解析失败返回空集）。 */
        private static Set<String> readPrtsAllowlist() {
            Set<String> set = new HashSet<String>();
            Path p = Paths.get("prts.yml");
            if (!Files.exists(p)) return set;
            try {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                boolean inGuard = false;
                boolean inAllow = false;
                for (String raw : lines) {
                    String line = raw.replace("\t", " ");
                    int indent = 0;
                    while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                    String trim = line.trim();
                    if (!inGuard) {
                        if (trim.startsWith("guard:") || trim.equals("guard:")) inGuard = true;
                        continue;
                    }
                    if (trim.startsWith("allowlist:")) { inAllow = true; continue; }
                    if (inAllow) {
                        if (indent <= 2 && !trim.startsWith("-")) { inAllow = false; continue; }
                        if (trim.startsWith("- ")) {
                            String id = trim.substring(2).trim().replace("\"", "").replace("'", "");
                            if (!id.isEmpty()) set.add(id.toLowerCase());
                        } else if (trim.startsWith("-")) {
                            String id = trim.substring(1).trim().replace("\"", "").replace("'", "");
                            if (!id.isEmpty()) set.add(id.toLowerCase());
                        } else if (indent <= 2) {
                            inAllow = false;
                        }
                    }
                }
            } catch (IOException ignored) {}
            return set;
        }
    }

    /** v10: 跨启动状态记忆。quarantined=我们隔离过的 modId（用于识别"用户加回"）；insistedFailed=连崩计数。 */
    public static final class GuardState {
        final Map<String, Info> quarantined = new LinkedHashMap<String, Info>();
        final Map<String, Info> insistedFailed = new LinkedHashMap<String, Info>();

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
            } catch (IOException ignored) {}
            return s;
        }

        void save() {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("{\n  \"version\": 2,\n  \"quarantined\": {\n");
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
            }
        } catch (IOException ignored) {}
    }
}
