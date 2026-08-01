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
 * 启动前扫描 mods 目录，隔离会导致服务端崩溃的客户端专用模组（参考 IMBlocker 案例），并运行期自愈。
 * 版本迭代史与判据实证见 docs/clientmodguard-iteration-log.md；代码注释一律 ≤2 行。
 */
public final class ClientModGuard {

    private static final Path QUARANTINE_DIR = Paths.get("_disabled_mods");
    // v17b：预检/自愈/状态/白名单等生成文件统一收敛到此目录（改动 B）
    private static final Path CLIENTCHECK_DIR = Paths.get("_clientcheck");
    // 运行时隔离失败（Windows 文件占用）时的同目录改名后缀；下次启动预扫描会把它真正移走。
    private static final String PENDING_SUFFIX = ".prts-quarantined";
    private static final Path PRECHECK_LOG = CLIENTCHECK_DIR.resolve("precheck.log");
    // v18: boot 期落盘的自愈重启命令行，服主可手工修正；自愈时优先复用，非法则回退自动重建。
    private static final Path LAUNCH_ARGS_FILE = CLIENTCHECK_DIR.resolve("launch.args");
    private static Path MODS_DIR;

    // 自愈重启上限（单次会话最多自动重启次数，防级联/失控）
    private static int MAX_RESTART = 5; // 兜底默认值，run() 里被配置覆盖
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

    // 双端守卫标记：DistExecutor/FMLEnvironment/FabricLoader（模组运行期主动分支检查 dist 后才跑客户端逻辑）。
    // v12 已剔除裸 Dist/EnvType（常量池无区分度，安全集命中率反高于客户端集）；仅作 L3 价值信号。
    private static final String[] DIST_GUARD_MARKERS = {
        "net/minecraftforge/fml/DistExecutor",
        "net/minecraftforge/fml/loading/FMLEnvironment",
        "net/neoforged/fml/DistExecutor",
        "net/neoforged/fml/loading/FMLEnvironment",
        "net/fabricmc/loader/api/FabricLoader"
    };

    // 宽泛守卫标记（@OnlyIn/EnvType/Environment 常量）：弱信号，只证明模组有过 dist 意识，不能判客户端性。
    // 未守卫客户端判定 = hasClient && !hasDistGuard && !hasBroadGuard，只抓零守卫的裸客户端模组。
    private static final String[] BROAD_GUARD_MARKERS = {
        "net/minecraftforge/api/distmarker/Dist",
        "Lnet/minecraftforge/api/distmarker/Dist",
        "net/fabricmc/api/Environment",
        "net/fabricmc/api/EnvType"
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

    // v18: 单 jar 内 mixin 类扫描上限，保险丝——超大配置不拖慢启动（两条检测路径共用，避免预算分叉）
    private static final int MIXIN_SCAN_BUDGET = 800;

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
    private static volatile boolean CUSTOM_FP_LOADED = false;
    private static String[] LAUNCH_ARGS = new String[0];
    private static long BOOT_TIME = System.currentTimeMillis();

    // v19b: 首次启动若 prts.yml 缺 guard 段，追加默认(关闭)配置，免服主手加；已有则跳过(幂等)
    private static void ensurePrtsGuardConfig() {
        Path p = Paths.get("prts.yml");
        if (!Files.exists(p)) return; // Arclight 尚未生成，下轮启动再补
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String l : lines) {
                if (l.trim().equals("guard:") || l.trim().startsWith("guard:")) return;
            }
            byte[] data = Files.readAllBytes(p);
            int n = data.length;
            boolean endsNL = n == 0 || data[n - 1] == '\n' || data[n - 1] == '\r';
            StringBuilder b = new StringBuilder();
            if (!endsNL) b.append("\n");
            b.append("\n");
            b.append("# 客户端模组守卫（自动生成；autoQuarantine=false 关闭隔离，仅报告）\n");
            b.append("guard:\n");
            b.append("  # 客户端模组自动隔离总开关：true=启用自动隔离，false=仅报告不挪动（默认关闭）\n");
            b.append("  autoQuarantine: false\n");
            b.append("  # 自愈重启上限（连续崩溃自动重启次数，0=不重启）\n");
            b.append("  maxRestarts: 5\n");
            Files.write(p, b.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            note("CONFIG_AUTOGEN prts.yml -> guard: autoQuarantine=false maxRestarts=5");
        } catch (IOException e) {
            note("CONFIG_AUTOGEN_FAIL prts.yml: " + e);
        }
    }

    public static void run() {
        run(new String[0]);
    }

    public static void run(String[] args) {
        if (args != null) LAUNCH_ARGS = args;
        BOOT_TIME = System.currentTimeMillis();
        ensurePrtsGuardConfig(); // 先于 load，保证本次启动即读到默认段
        Config cfg = Config.load();
        MAX_RESTART = cfg.maxRestarts > 0 ? cfg.maxRestarts : 5;
        persistLaunchArgs(LAUNCH_ARGS);
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

    /** shutdown hook 自愈：JVM 退出时若有本次启动后生成的客户端类缺失崩溃报告，隔离 offending mod 并重启；正常关服静默返回（hook 内禁 System.exit 防死锁）。 */
    static void shutdownHeal() {
        if (SELF_HEALED) return; // handleCrash 已处理过

        Path dir = MODS_DIR != null && Files.isDirectory(MODS_DIR)
            ? MODS_DIR : Paths.get(System.getProperty("fml.modsDir", "mods"));
        List<Path> jars = new ArrayList<Path>();
        try { collectJars(dir, jars); } catch (IOException ignored) {}
        Config guardCfg = Config.load();

        Map<String, Object[]> offenders = new LinkedHashMap<String, Object[]>();

        // 路径 A（v10）：崩溃报告里明确写着"某模组因缺失客户端类而加载失败"。
        List<String[]> failures = parseCrashReportClientFailures(BOOT_TIME);
        for (String[] pr : failures) {
            Path jar = findJarByModId(jars, pr[0]);
            if (jar != null) offenders.put(pr[0], new Object[]{jar, "runtime: " + pr[1]});
        }
        if (!offenders.isEmpty()) note("SHUTDOWN_HEAL detected clientFailures=" + failures.size());

        // 路径 B（v15）：崩溃报告只有"X has class loading errors"，无客户端类名，但 Forge 已认定该模组搞挂服务端。
        // 仅当该 jar 确有客户端代码特征才隔离（零误删底线），否则只记录不动手。
        if (offenders.isEmpty()) {
            List<String[]> modFails = parseCrashReportModFailures(BOOT_TIME);
            for (String[] pr : modFails) {
                Path jar = findJarByModIdOrName(jars, pr[0]);
                if (jar == null) { note("SHUTDOWN_HEAL_SKIP no-jar " + pr[0]); continue; }

                // v17 守护者归因：先判失败是否由作者点名他人并要求移除；命中则隔离被点名真凶（含同家族姊妹包）、保住守护者，不新增隔离对象。
                List<Path> culprits = resolveGuardianCulprit(pr.length > 2 ? pr[2] : "", jar, jars, guardCfg);
                if (!culprits.isEmpty()) {
                    StringBuilder names = new StringBuilder();
                    for (Path c : culprits) {
                        String cid;
                        try { cid = detectModMeta(c).modId; }
                        catch (Throwable e) { cid = c.getFileName().toString(); }
                        offenders.put(cid, new Object[]{c, "runtime: 被 " + jar.getFileName()
                            + " 检测为服务端不兼容并要求移除（守护者归因）"});
                        if (names.length() > 0) names.append(", ");
                        names.append(c.getFileName().toString());
                    }
                    System.err.println("[PRTS] " + jar.getFileName() + " 点名以下模组为服务端不兼容并要求移除，已按家族隔离被点名者，保留报告者：" + names);
                    note("GUARDIAN_ATTRIB " + jar.getFileName() + " -> " + names);
                    continue;
                }

                ScanResult r = null;
                try { r = scanJarFull(jar); } catch (Throwable ignored) {}
                if (r == null || !r.hasClient) {
                    System.err.println("[PRTS] Forge 报告模组加载失败: " + pr[0]
                        + "，但该模组无客户端代码特征，不予自动隔离（可能是真实故障，请查崩溃报告）。");
                    note("SHUTDOWN_HEAL_SKIP no-client-code " + pr[0]);
                    continue;
                }
                String modId;
                try { modId = detectModMeta(jar).modId; } catch (Throwable e) { modId = jar.getFileName().toString(); }
                offenders.put(modId, new Object[]{jar, "runtime: " + pr[1]});
            }
            if (!offenders.isEmpty()) note("SHUTDOWN_HEAL detected modLoadFailures=" + offenders.size());
        }

        // 路径 C（v16b）：崩溃特征只在日志里（Forge 吞异常并 System.exit，无 crash-report）。
        // 扫 logs/latest.log 尾部，命中"*.mixins.json 在专用服注入失败"即归属属主并隔离；仅真崩时触发。
        if (offenders.isEmpty()) {
            for (String cfg : parseMixinConfigNamesFromLog()) {
                Path jar = findJarContainingEntry(jars, cfg);
                if (jar == null) jar = findJarByModIdOrName(jars, cfg.replaceFirst("\\.mixins?\\.json$", ""));
                if (jar == null) continue;
                String mid;
                try { mid = detectModMeta(jar).modId; } catch (Throwable e) { mid = jar.getFileName().toString(); }
                offenders.put(mid, new Object[]{jar, "runtime: Mixin 配置 " + cfg
                    + " 在专用服注入失败（客户端类缺失，已自动隔离）"});
                note("MIXIN_CFG_SELFHEAL " + cfg + " -> " + jar.getFileName());
                break;
            }
        }

        if (offenders.isEmpty()) {
            if (!failures.isEmpty()) note("SHUTDOWN_HEAL cannot locate jars for failures");
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
                System.err.println("[PRTS] 如需强制保留，请在 _clientcheck/allowlist.json 的 allowlist 加入 \"" + modId + "\"（或 prts.yml guard.allowlist）；否则请将其移出 mods/。");
            }
        }
        state.save();
        return true;
    }

    /** 运行时自愈入口：捕获 Main_Forge.main 抛出的"缺失客户端类/模组加载失败"异常，定位并隔离 offending mod 后重启。 */
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

            // 路径0.5（v16，最高优先）：Mixin APPLY 崩溃消息直接带配置名（如 betterlockon.mixins.json:...FAILED during APPLY），配置名即属主模组，精度最高。
            // 仅真崩（消息含 mixin 配置名）时触发，正常运行模组（如大量指向 EpicFight 客户端类的 CombatEvolution/tcrcore）从不触发，零误伤。
        if (offenders.isEmpty()) {
            for (String cfg : parseMixinConfigNames(t)) {
                Path jar = findJarContainingEntry(jars, cfg);
                if (jar == null) jar = findJarByModIdOrName(jars, cfg.replaceFirst("\\.mixins?\\.json$", ""));
                if (jar == null) continue;
                String mid;
                try { mid = detectModMeta(jar).modId; } catch (Throwable e) { mid = jar.getFileName().toString(); }
                offenders.put(mid, new Object[]{jar, "runtime: Mixin 配置 " + cfg
                    + " 在专用服注入失败（客户端类缺失，已自动隔离）"});
                note("MIXIN_CFG_SELFHEAL " + cfg + " -> " + jar.getFileName());
                break;
            }
        }

        // 路径1: 异常链本身含客户端类名（NoClassDefFoundError 直达 main）
        if (direct) {
            Path mod = locateOffendingMod(t);
            if (mod != null) {
                String modId;
                try { modId = detectModMeta(mod).modId; } catch (Throwable e) { modId = mod.getFileName().toString(); }
                // v16：定位到的模组可能只是「守护者」（自身双端安全，只是检测到别的模组缺客户端类而报错，
                // 如 tcrcore 点名 aaa_particles）。此时应隔离异常消息里点名的真凶，保留守护者。
                Path real = resolveRealCulprit(t, mod, jars);
                if (real != null && !real.equals(mod)) {
                    String realId;
                    try { realId = detectModMeta(real).modId; } catch (Throwable e) { realId = real.getFileName().toString(); }
                    offenders.put(realId, new Object[]{real, "runtime: v16 隔离真凶 " + real.getFileName()
                        + "（守护者 " + mod.getFileName() + " 保留）"});
                } else {
                    offenders.put(modId, new Object[]{mod, "runtime: " + missingClassName(t)});
                }
            }
        }
            // 路径1.5（v14）: Forge 的 LoadingFailedException 直接在消息里【点名】失败模组（"X has class loading errors"），精度最高且不依赖 crash-report 落盘。
            // 为守零误删底线：仅当该 jar 确含客户端代码特征才隔离，否则只告警交还正常崩溃。
        if (offenders.isEmpty() && modLoadFail) {
            for (String name : parseLoadingFailureNames(t)) {
                Path jar = findJarByModIdOrName(jars, name);
                if (jar == null) continue;
                ScanResult r = null;
                try { r = scanJarFull(jar); } catch (Throwable ignored) {}
                if (r == null || !r.hasClient) {
                    System.err.println("[PRTS] Forge 报告模组加载失败: " + name
                        + "，但该模组无客户端代码特征，不予自动隔离（可能是真实故障，请查日志）。");
                    note("LOADFAIL_SKIP " + name);
                    continue;
                }
                String modId;
                try { modId = detectModMeta(jar).modId; } catch (Throwable e) { modId = jar.getFileName().toString(); }
                offenders.put(modId, new Object[]{jar, "runtime: Forge 报告加载失败(" + name + ")"});
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

    /** 子线程感知运行时自愈：兜底"子线程懒加载客户端类失败"盲区（主线程 try/catch 与 shutdown hook 都抓不到）。 */
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
            Path newest = newestCrashReport(sinceMillis);
            if (newest == null) return out;
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

    /** v15：解析崩溃报告中 Forge 点名的加载失败模组（段内 Failure message 或 mixin 配置属主），只取证不隔离。 */
    static List<String[]> parseCrashReportModFailures(long sinceMillis) {
        List<String[]> out = new ArrayList<String[]>();
        try {
            Path newest = newestCrashReport(sinceMillis);
            if (newest == null) return out;
            List<String> lines = Files.readAllLines(newest, StandardCharsets.UTF_8);

            String curMod = null;
            Pattern sec = Pattern.compile("^--\\s*MOD\\s+([\\w.-]+)\\s*--\\s*$");
            Pattern mix = Pattern.compile("Mixin \\[([\\w.-]+)\\.mixins\\.json:");
            Set<String> seen = new LinkedHashSet<String>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                Matcher ms = sec.matcher(line);
                if (ms.matches()) { curMod = ms.group(1); continue; }
                if (line.startsWith("Failure message:")) {
                    String msg = line.substring("Failure message:".length()).trim();
                    String id = curMod;
                    if (id == null) { // 退化：从消息里取模组名
                        int k = msg.indexOf(" has ");
                        if (k > 0) id = msg.substring(0, k).trim();
                    }
                    if (id != null && seen.add(id)) {
                        out.add(new String[]{id, "Forge 报告加载失败(" + msg + ")",
                            collectAttribution(lines, i, sec)});
                    }
                }
                Matcher mm = mix.matcher(line);
                if (mm.find()) {
                    String owner = mm.group(1);
                    // 排除 mixin 配置名等于当前段模组本身的情况（那已由上面的 Failure message 覆盖）
                    if (seen.add(owner)) {
                        out.add(new String[]{owner, "其 mixin 配置 " + owner
                            + ".mixins.json 在专用服上注入失败并拖垮模组加载", ""});
                    }
                }
            }
            if (!out.isEmpty()) note("CRASHREPORT_MODFAIL " + newest.getFileName() + " candidates=" + out.size());
        } catch (Throwable e) { note("CRASHREPORT_MODFAIL_PARSE_FAIL " + e); }
        return out;
    }

    /** v17：从 Failure message 行起向下收集同段归因文本（≤8 行，遇段落/堆栈边界即止），供守护者归因判定使用。 */
    private static String collectAttribution(List<String> lines, int start, Pattern secPattern) {
        StringBuilder sb = new StringBuilder();
        for (int j = start; j < lines.size() && j <= start + 8; j++) {
            String s = lines.get(j).trim();
            if (s.isEmpty()) continue;
            if (j > start) {
                // 段落边界：下一个模组段 / 堆栈 / 下一条失败记录
                if (secPattern.matcher(s).matches()) break;
                if (s.startsWith("Stacktrace:") || s.startsWith("Mod File:")
                    || s.startsWith("Failure message:") || s.startsWith("-- ")) break;
            }
            sb.append(s).append('\n');
        }
        return sb.toString();
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

    /** v14: 从 Forge LoadingFailedException 消息中提取被点名的模组（展示名或 modId）。 */
    static List<String> parseLoadingFailureNames(Throwable t) {
        List<String> out = new ArrayList<String>();
        Throwable c = t;
        while (c != null) {
            String msg = c.getMessage();
            if (msg != null && msg.contains("Loading errors encountered")) {
                for (String raw : msg.split("\\r?\\n")) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("Loading errors encountered")) continue;
                    int cut = -1;
                    for (String kw : new String[]{" has class loading errors", " has failed to load correctly",
                        " has mod loading errors", " encountered an error"}) {
                        int k = line.indexOf(kw);
                        if (k > 0) { cut = k; break; }
                    }
                    if (cut <= 0) continue;
                    String name = line.substring(0, cut).trim();
                    int par = name.indexOf('(');
                    if (par > 0) {
                        String inner = name.substring(par + 1).replace(")", "").trim();
                        if (!inner.isEmpty() && !out.contains(inner)) out.add(inner);
                        name = name.substring(0, par).trim();
                    }
                    if (!name.isEmpty() && !out.contains(name)) out.add(name);
                }
            }
            c = c.getCause();
        }
        return out;
    }

    /** v14: 按 modId / displayName / 文件名前缀（忽略大小写、空格、下划线、连字符）反查 jar。 */
    private static Path findJarByModIdOrName(List<Path> jars, String name) {
        String key = normName(name);
        if (key.isEmpty()) return null;
        for (Path jar : jars) {
            try {
                ModMeta m = detectModMeta(jar);
                if (m.modId != null && normName(m.modId).equals(key)) return jar;
                if (m.displayName != null && normName(m.displayName).equals(key)) return jar;
            } catch (Throwable ignored) {}
        }
        for (Path jar : jars) {
            if (normName(jar.getFileName().toString()).startsWith(key)) return jar;
        }
        return null;
    }

    private static String normName(String s) {
        if (s == null) return "";
        return s.toLowerCase().replace(" ", "").replace("_", "").replace("-", "").replace(".", "");
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
        migrateLegacyFiles();
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
        if (cfg.sourceFile != null) note("CONFIG " + cfg.sourceFile);
        loadCustomFingerprints();
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
        if (!d.byPoisonMixin.isEmpty()) {
            System.out.println("[PRTS] 中毒 mixin 命中（注入原版服务端类却调用客户端类，加载即崩且不写崩溃报告）: ");
            for (String s : d.byPoisonMixin) System.out.println("[PRTS]   - " + s);
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
            System.err.println("[PRTS] 注意：以下依赖关系因目标模组命中硬证据（已确证客户端/崩服）而【未】回补，");
            System.err.println("[PRTS]       依赖方已一并连坐隔离——留着孤儿会让 Forge 报缺依赖并中断模组装配，");
            System.err.println("[PRTS]       连 Arclight 核心 mod 都加载不到，整包直接起不来，故优先保证可启动：");
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
                System.out.println("[PRTS]       如需一并清理，请在 _clientcheck/allowlist.json 设 \"pruneHarmlessClientMods\": true");
            }
            for (String s : d.amber) note("  AMBER " + s);
        }
        if (!cfg.autoQuarantine && !d.reported.isEmpty()) {
            System.out.println("[PRTS] autoQuarantine=false，以下仅报告未隔离: " + String.join(", ", d.reported));
        }
        if (moved) {
            System.out.println("[PRTS] 已将疑似客户端模组隔离至 " + QUARANTINE_DIR.toAbsolutePath());
            System.out.println("[PRTS] 若系误判，请移回 mods 并在 _clientcheck/whitelist.json 的 allowlist 加入其 modId/文件名");
        } else if (d.toQuarantine.isEmpty() && d.reported.isEmpty()) {
            System.out.println("[PRTS] 客户端模组预检：未发现疑似客户端专用模组");
        }
        System.out.println("[PRTS] 客户端模组预检结束: 隔离 " + d.toQuarantine.size()
            + " 个 / 保留 " + d.keepCount + " 个，继续启动服务端...");
        note("DONE quarantined=" + d.toQuarantine.size() + " kept=" + d.keepCount
            + " fingerprint=" + d.byFingerprint.size() + " poisonMixin=" + d.byPoisonMixin.size()
            + " clientTargetMixin=" + d.byClientTargetMixin.size()
            + " chained=" + d.chained.size()
            + " reported=" + d.reported.size() + " amber=" + d.amber.size()
            + " trustedConflict=" + d.trustedConflict.size()
            + (moved ? " (moved)" : (d.toQuarantine.isEmpty() && d.reported.isEmpty() ? " (none)" : " (report-only)")));
        for (String s : d.byFingerprint) note("  FINGERPRINT " + s);
        for (String s : d.byPoisonMixin) note("  POISON_MIXIN " + s);
        for (String s : d.byClientTargetMixin) note("  CLIENT_TARGET_MIXIN(观察,不隔离) " + s);
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

            // v10: 用户加回的模组=显式覆盖，跳过预隔离；若真崩，运行时自愈会再抓（误伤如 ae2ct 则保留成功）。
            boolean restored = state.quarantined.containsKey(id);
            if (restored) {
                d.keepCount++;
                d.restored.add(fn);
                GuardState.Info fi = state.insistedFailed.get(id);
                if (fi != null && fi.at >= 2) {
                    // 连崩告警：shutdown hook 里的 System.err 会被 log4j 关闭吞掉，故在预扫描阶段（控制台可见）重复告警
                    System.err.println("[PRTS] 警告：模组 '" + id + "' (" + fn + ") 曾连续 " + fi.at + " 次因缺失客户端类崩溃后被自动隔离，现已被加回。");
                    System.err.println("[PRTS]       它几乎可以确定是客户端专用模组。若坚持保留请加入 _clientcheck/allowlist.json 的 allowlist；否则请将其移出 mods/，避免反复崩溃重启。");
                    note("INSISTED_WARN " + fn + " modId=" + id + " fails=" + fi.at);
                }
                sig(fn, id, m, r, cfg.isTrusted(id, fn), "KEEP", "L0/user-restored");
                continue;
            }

            boolean envClient = "CLIENT".equalsIgnoreCase(m.environment);
            boolean cso = m.clientSideOnly; // 根级 clientSideOnly=true（Forge 专用服会跳过）
            boolean envServer = "SERVER".equalsIgnoreCase(m.environment) || "BOTH".equalsIgnoreCase(m.environment);

            // v12 P0-1：hasDistGuard/hasKjsPlugin 不再豁免 suspect（实测两集分布几乎相同，当免死金牌造成 61% 漏检），降级为 L3 VALUE 信号。
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

            // v15 L1：中毒 mixin（见 detectPoisonMixin），启动前拦下，否则服务端起不来且不写崩溃报告，运行期自愈抓不到。
            if (r != null && r.poisonMixin != null && !inWhite) {
                d.toQuarantine.add(jar);
                d.hardQuarantined.add(jar);
                d.byPoisonMixin.add(fn + " [" + r.poisonMixin + "]");
                if (trusted) d.trustedConflict.add(fn + " [中毒 mixin: " + r.poisonMixin + "]");
                sig(fn, id, m, r, trusted, "QUARANTINE", "L1/poison-mixin");
                continue;
            }

            // v16：客户端目标 mixin 只观察不隔离——实测 3 命中全误判（专用服不加载 net/minecraft/client/**，mixin 静躺不崩），降级 AMBER。
            if (r != null && r.clientTargetMixin != null) {
                d.byClientTargetMixin.add(fn + " [" + r.clientTargetMixin + "]");
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
            } else if (envClient || cso) {
                // L1 硬证据：模组自声明 CLIENT 或根级 clientSideOnly=true（Forge 专用服会跳过），复刻 Forge 行为。
                v = cfg.autoQuarantine ? Verdict.QUARANTINE : Verdict.REPORT;
                src = "L1/declared-client" + (cso && !envClient ? "[clientSideOnly]" : "");
                if (trusted && v == Verdict.QUARANTINE)
                    d.trustedConflict.add(fn + " [mods.toml 自声明 " + (envClient ? "CLIENT" : "clientSideOnly=true") + "]");
            } else if (isUnguardedClient(r)) {
                // 软信号（非硬证据）：引用客户端类且无任何服务端信号/分服务端守卫。字节扫描无法 100% 区分纯客户端与极简双端，故只观察不隔离。
                v = Verdict.REPORT;
                src = "L2/unguarded-client";
                d.amber.add(fn + " [引用客户端类且无服务端信号，已保留观察]");
            } else if (cfg.strictMode && r != null && r.hasClient) {
                // 严格模式（opt-in）：移除一切引用客户端类的模组，追求纯净服务端，可能误删双端模组，默认关闭。
                v = cfg.autoQuarantine ? Verdict.QUARANTINE : Verdict.REPORT;
                src = "L1/strict-client";
                if (trusted && v == Verdict.QUARANTINE)
                    d.trustedConflict.add(fn + " [strict 模式: 含客户端引用]");
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
                    // v12 P0-3：证据不足→AMBER 保留+观察+报告（留不崩服模组代价几 MB，误删库代价整包起不来）。
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
                    if ("L0/blacklist".equals(src) || "L1/declared-client".equals(src)
                        || "L1/strict-client".equals(src)) {
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

                    // 硬证据（指纹/黑名单/自声明 CLIENT）不得被依赖闭包回补：缺依赖顶多功能异常，放回崩服模组则整包起不来。
                    // 实测 MaFgLib/fancymenu/konkrete/mekalus(oculus 簇) 旧逻辑全漏网。
                    if (d.hardQuarantined.contains(depJar)) {
                        // 硬证据目标绝不回补，但依赖方必须连坐隔离：孤儿依赖方会令 Forge 抛缺依赖、中断装配、整包起不来（连 Arclight 自身 jar 都加载不到）。
                        // 连坐对象同样标记为硬证据，保证多级依赖链（A->B->C）一路传递。
                        d.brokenDeps.add(m.modId + " 强依赖已隔离的 " + dep
                            + "（" + depJar.getFileName() + "，硬证据不予回补）→ 依赖方 "
                            + jar.getFileName() + " 一并连坐隔离，否则 Forge 缺依赖会导致整包无法启动");
                        d.toQuarantine.add(jar);
                        d.hardQuarantined.add(jar);
                        quarantinedIds.put(m.modId, jar);
                        d.chained.add(jar.getFileName() + " [强依赖已隔离的 " + dep + "（硬证据），连坐隔离]");
                        d.keepCount--;
                        changed = true;
                        break;
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
        // v16: 客户端目标 mixin 观察集（只上报不隔离，实测非崩溃预测信号，详见 decide() 内注释）
        final List<String> byPoisonMixin = new ArrayList<String>();
        // v16: 客户端目标 mixin 隔离集（@Mixin 目标本身是客户端类的模组，专用服必 InvalidMixinException 崩）
        final List<String> byClientTargetMixin = new ArrayList<String>();
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
            + " broad=" + bit(r != null && r.hasBroadGuard)
            + " kjs=" + bit(r != null && r.hasKjsPlugin)
            + " poison=" + bit(r != null && r.poisonMixin != null)
            + " clientTarget=" + bit(r != null && r.clientTargetMixin != null)
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

    /** v12 原子隔离：优先 ATOMIC_MOVE，失败则「复制→校验哈希→删源→确认消失」；删源失败必须回滚，否则 mods/ 与隔离区同名共存。 */
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
            // 关键：删源失败必须回滚，否则就复现了 mods/ 与 _disabled_mods/ 同时存在的 bug
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw delFailed;
        }
        if (Files.exists(jar)) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw new IOException("隔离后源文件仍存在: " + jar.getFileName());
        }
    }

    /** v12 启动时清隔离残留：仅比同名文件，sha1 相同则删 mods/ 侧（隔离区已有副本），不同则告警交人工。 */
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

    /** 运行时隔离：先移动，占用失败则同目录改名兜底（放宽到 IOException 以不中断自愈）。 */
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
            r.poisonMixin = detectPoisonMixin(jf, jar);
        } catch (IOException ignored) {}
        return r;
    }

    // ==================== v15：中毒 mixin 静态检测（L1 硬证据，启动前） ====================
    // 注入原版必加载服务端类且体内调用客户端类的 mixin 专用服加载即 MixinTransformerError 且不写崩溃报告，须启动前拦截；五条判据同时满足才隔离（环境非CLIENT、无CLIENT注解/@Pseudo、常量池真调用客户端类、@Mixin目标为原版非客户端类）。
    private static String detectPoisonMixin(JarFile jf, Path jar) {
        List<String> cfgs = collectMixinConfigs(jf);
        int budget = MIXIN_SCAN_BUDGET;
        for (String cfgName : cfgs) {
            JarEntry ce = jf.getJarEntry(cfgName);
            if (ce == null || ce.getSize() > 1024L * 1024L) continue;
            String json;
            try (InputStream is = jf.getInputStream(ce)) {
                json = new String(readAll(is), StandardCharsets.UTF_8);
            } catch (IOException ex) { continue; }
            String env = jsonString(json, "environment");
            if (env != null && env.equalsIgnoreCase("CLIENT")) continue;
            String pkg = jsonString(json, "package");
            String prefix = pkg == null ? "" : pkg.replace('.', '/') + "/";
            List<String> classes = new ArrayList<String>();
            collectJsonStringArray(json, "mixins", classes);
            collectJsonStringArray(json, "server", classes);
            for (String cls : classes) {
                if (--budget < 0) return null;
                JarEntry me = jf.getJarEntry(prefix + cls.replace('.', '/') + ".class");
                if (me == null || me.getSize() > 512L * 1024L) continue;
                byte[] b;
                try (InputStream is = jf.getInputStream(me)) { b = readAll(is); }
                catch (IOException ex) { continue; }
                if (!contains(b, "net/minecraft/client/") && !contains(b, "com/mojang/blaze3d/")) continue;
                MixinClassInfo ci = readMixinClass(b);
                if (ci == null || !ci.callsClient || ci.classEnvClient || ci.pseudo) continue;
                String target = null;
                for (String t : ci.targets) {
                    if (t.startsWith("net/minecraft/") && !t.startsWith("net/minecraft/client/")) { target = t; break; }
                }
                if (target == null) continue;
                String desc = cfgName + ":" + cls + " -> " + target;
                if (ci.memberEnvClient) {
                    note("  MIXIN_WATCH " + jar.getFileName() + " " + desc
                        + " (成员带 dist 注解，运行期可能被剥离，不隔离)");
                    continue;
                }
                return desc;
            }
        }
        return null;
    }

    /** v16 与 detectPoisonMixin 对称：@Mixin 目标本身是 net/minecraft/client/** 类，专用服必 InvalidMixinException 崩，须提前隔离。 */
    private static String detectClientTargetMixin(JarFile jf, Path jar) {
        List<String> cfgs = collectMixinConfigs(jf);
        int budget = MIXIN_SCAN_BUDGET;
        for (String cfgName : cfgs) {
            JarEntry ce = jf.getJarEntry(cfgName);
            if (ce == null || ce.getSize() > 1024L * 1024L) continue;
            String json;
            try (InputStream is = jf.getInputStream(ce)) { json = new String(readAll(is), StandardCharsets.UTF_8); }
            catch (IOException ex) { continue; }
            String env = jsonString(json, "environment");
            if (env != null && env.equalsIgnoreCase("CLIENT")) continue;
            // 声明了 IMixinConfigPlugin：插件可在运行期按 dist 过滤 shouldApplyMixin，
            // 静态无法断定会崩，放弃该配置以免误伤双端模组（零误删优先）。
            if (jsonString(json, "plugin") != null) continue;
            // required=false：Mixin 应用失败仅告警不致命，不作为隔离依据。
            if (json.replaceAll("\\s", "").contains("\"required\":false")) continue;
            String pkg = jsonString(json, "package");
            String prefix = pkg == null ? "" : pkg.replace('.', '/') + "/";
            List<String> classes = new ArrayList<String>();
            collectJsonStringArray(json, "mixins", classes);
            collectJsonStringArray(json, "server", classes);
            for (String cls : classes) {
                if (--budget < 0) return null;
                JarEntry me = jf.getJarEntry(prefix + cls.replace('.', '/') + ".class");
                if (me == null || me.getSize() > 512L * 1024L) continue;
                byte[] b;
                try (InputStream is = jf.getInputStream(me)) { b = readAll(is); }
                catch (IOException ex) { continue; }
                MixinClassInfo ci = readMixinClass(b);
                // 类自身带 CLIENT dist 注解或 @Pseudo：运行期行为不确定，保守跳过（零误删优先）。
                if (ci == null || ci.targets.isEmpty() || ci.classEnvClient || ci.pseudo) continue;
                for (String t : ci.targets) {
                    if (t.startsWith("net/minecraft/client/")) return cfgName + ":" + cls + " -> " + t;
                }
            }
        }
        return null;
    }

    /** 取 JSON 顶层字符串数组（与 collectCommonMixinClasses 同级的轻量解析，不引入 JSON 库）。 */
    static void collectJsonStringArray(String json, String key, List<String> out) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return;
        int lb = json.indexOf('[', i);
        if (lb < 0) return;
        int rb = json.indexOf(']', lb);
        if (rb < 0) return;
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(json.substring(lb + 1, rb));
        while (m.find()) out.add(m.group(1));
    }

    static final class MixinClassInfo {
        final List<String> targets = new ArrayList<String>(); // @Mixin(value=/targets=) 目标内部名
        boolean callsClient;     // 常量池里有 owner 为客户端包的 Methodref/Fieldref/InterfaceMethodref
        boolean classEnvClient;  // 类上 @Environment(CLIENT)/@OnlyIn(CLIENT)
        boolean memberEnvClient; // 任一字段/方法上 @Environment(CLIENT)/@OnlyIn(CLIENT)
        boolean pseudo;          // @Pseudo：目标缺失可容忍
    }

    /** 极简 class 文件读取游标（只读常量池 + 注解，不依赖 ASM，applaunch 阶段没有第三方库可用）。 */
    private static final class Cur {
        final byte[] d; int p; String[] utf;
        Cur(byte[] d) { this.d = d; }
        int u1() { return d[p++] & 0xFF; }
        int u2() { int v = ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF); p += 2; return v; }
        int u4() {
            int v = ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            p += 4; return v;
        }
        String utf(int i) { return (i > 0 && utf != null && i < utf.length) ? utf[i] : null; }
    }

    private static MixinClassInfo readMixinClass(byte[] data) {
        try {
            Cur c = new Cur(data);
            if (c.u4() != 0xCAFEBABE) return null;
            c.u2(); c.u2(); // minor / major
            int cpn = c.u2();
            String[] utf = new String[cpn];
            int[] clsName = new int[cpn]; // CONSTANT_Class -> name_index
            int[] refCls = new int[cpn];  // Field/Method/InterfaceMethodref -> class_index
            for (int i = 1; i < cpn; i++) {
                int tag = c.u1();
                if (tag == 1) { int len = c.u2(); utf[i] = new String(data, c.p, len, StandardCharsets.UTF_8); c.p += len; }
                else if (tag == 7) clsName[i] = c.u2();
                else if (tag == 8 || tag == 16 || tag == 19 || tag == 20) c.p += 2;
                else if (tag == 15) c.p += 3;
                else if (tag == 9 || tag == 10 || tag == 11) { refCls[i] = c.u2(); c.p += 2; }
                else if (tag == 3 || tag == 4 || tag == 12 || tag == 17 || tag == 18) c.p += 4;
                else if (tag == 5 || tag == 6) { c.p += 8; i++; } // long/double 占两个槽位
                else return null;
            }
            c.utf = utf;
            MixinClassInfo info = new MixinClassInfo();
            for (int i = 1; i < cpn && !info.callsClient; i++) {
                int ci = refCls[i];
                if (ci <= 0 || ci >= cpn) continue;
                int ni = clsName[ci];
                String owner = (ni > 0 && ni < cpn) ? utf[ni] : null;
                if (owner != null && (owner.startsWith("net/minecraft/client/") || owner.startsWith("com/mojang/blaze3d/")))
                    info.callsClient = true;
            }
            c.p += 6;          // access_flags / this_class / super_class
            // 注意：不能写成 c.p += c.u2() * 2 —— 复合赋值会先取旧的 c.p，
            // 导致 u2() 已消费的 2 字节被抹掉，整个后续解析错位。必须分两步。
            int ifaceCount = c.u2();
            c.p += ifaceCount * 2; // interfaces
            for (int k = 0; k < 2; k++) { // fields, methods
                int n = c.u2();
                for (int i = 0; i < n; i++) {
                    c.p += 6; // access_flags / name_index / descriptor_index
                    readAttrs(c, info, false);
                }
            }
            readAttrs(c, info, true);
            return info;
        } catch (RuntimeException e) {
            return null; // 解析不了就当作无证据，宁可漏检不可误删
        }
    }

    private static void readAttrs(Cur c, MixinClassInfo info, boolean classLevel) {
        int an = c.u2();
        for (int i = 0; i < an; i++) {
            String name = c.utf(c.u2());
            int len = c.u4();
            int end = c.p + len;
            if ("RuntimeVisibleAnnotations".equals(name) || "RuntimeInvisibleAnnotations".equals(name)) {
                int na = c.u2();
                for (int k = 0; k < na; k++) readAnno(c, info, classLevel);
            }
            c.p = end;
        }
    }

    private static void readAnno(Cur c, MixinClassInfo info, boolean classLevel) {
        String type = c.utf(c.u2());
        boolean isMixin = "Lorg/spongepowered/asm/mixin/Mixin;".equals(type);
        boolean isEnv = "Lnet/fabricmc/api/Environment;".equals(type)
            || "Lnet/minecraftforge/api/distmarker/OnlyIn;".equals(type);
        if ("Lorg/spongepowered/asm/mixin/Pseudo;".equals(type)) info.pseudo = true;
        int np = c.u2();
        for (int i = 0; i < np; i++) {
            String pn = c.utf(c.u2());
            readElem(c, info, isMixin && ("value".equals(pn) || "targets".equals(pn)), isEnv, classLevel);
        }
    }

    private static void readElem(Cur c, MixinClassInfo info, boolean collect, boolean envAnno, boolean classLevel) {
        int tag = c.u1();
        switch (tag) {
            case 'c': { // class 常量：@Mixin(Foo.class)
                String d = c.utf(c.u2());
                if (collect && d != null && d.length() > 2 && d.charAt(0) == 'L' && d.endsWith(";"))
                    info.targets.add(d.substring(1, d.length() - 1));
                break;
            }
            case 's': { // 字符串：@Mixin(targets = "a.b.C")
                String s = c.utf(c.u2());
                if (collect && s != null) info.targets.add(s.replace('.', '/'));
                break;
            }
            case 'e': { // 枚举：@OnlyIn(Dist.CLIENT) / @Environment(EnvType.CLIENT)
                c.u2();
                String cn = c.utf(c.u2());
                if (envAnno && "CLIENT".equals(cn)) {
                    if (classLevel) info.classEnvClient = true; else info.memberEnvClient = true;
                }
                break;
            }
            case '@': readAnno(c, info, classLevel); break;
            case '[': { int n = c.u2(); for (int i = 0; i < n; i++) readElem(c, info, collect, envAnno, classLevel); break; }
            default: c.p += 2; break; // B C D F I J S Z：常量池索引
        }
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
        if (!r.hasBroadGuard) {
            for (String m : BROAD_GUARD_MARKERS) if (contains(b, m)) { r.hasBroadGuard = true; break; }
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

    /** 缓存键=算法版本+文件名+大小+修改时间；改扫描判据须 bump SCAN_ALGO_VERSION，否则旧缓存信号失准。 */
    private static final String SCAN_ALGO_VERSION = "v14";

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
                    boolean inAnySection = false; // 是否已进入任意 [section]（含 [[mods]]）；根级声明必须在首个 section 之前
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
                            inAnySection = true;
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
                        // v14: 记录展示名，供反查 Forge 用展示名点名的失败模组。
                        if (meta.displayName == null) {
                            String dn = tomlValue(t, "displayName");
                            if (dn != null && !dn.isEmpty()) meta.displayName = dn;
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
                        // v12b: 复刻 Forge——仅根级（首个[section]前）clientSideOnly=true 被读取并跳过；[[mods]] 内部声明 Forge 不读，故不处理。
                        if (!inAnySection) {
                            String cso = tomlValue(t, "clientSideOnly");
                            if (cso != null && "true".equalsIgnoreCase(cso)) {
                                meta.clientSideOnly = true;
                            }
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
                    if (meta.displayName == null) meta.displayName = jsonString(fs, "name");
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
        boolean hasBroadGuard;  // 宽泛守卫(@OnlyIn/EnvType/Environment 常量)：至少有过 dist 意识
        boolean hasKjsPlugin;   // KubeJS 插件(kubejs.plugins.txt)：双端 KubeJS 附属
        // v15: 中毒 mixin —— 非空表示「注入服务端必加载的原版类 + 体内调用客户端类」，专用服上必崩。
        // v16: 客户端目标 mixin —— @Mixin 目标本身是 net/minecraft/client/** 下的客户端类，专用服上必 InvalidMixinException 崩。
        String poisonMixin;
        // 非空表示该类模组必崩（betterlockon 等 @Mixin 目标在 net/minecraft/client/** 下）。
        String clientTargetMixin;
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
        String displayName; // v14: mods.toml displayName / fabric.mod.json name，用于反查 Forge 报错点名的模组
        String environment;
        boolean clientSideOnly; // 根级 clientSideOnly=true（Forge 专用服会跳过该模组）
        final Set<String> dependencies = new HashSet<String>();
    }

    private enum Verdict { KEEP, QUARANTINE, REPORT }

    // ===================== 运行时自愈 =====================

    /** v16：定位到的模组若为守护者（自身双端安全，仅点名他人），从异常消息反查并隔离被点名的真·客户端模组，保留守护者。 */
    private static Path resolveRealCulprit(Throwable t, Path located, List<Path> jars) {
        // 1) 定位到的模组本身就是纯客户端模组 → 它即真凶，无需改判
        ScanResult lr = null;
        try { lr = scanJarFull(located); } catch (Throwable ignored) {}
        if (isUnguardedClient(lr)) {
            return located;
        }
        // 2) 定位到的是「守护者」：从异常消息里找被点名的真·客户端模组（如 tcrcore 点名 aaa_particles）
        String msg = normalizeForMatch(summarize(t));
        for (Path jar : jars) {
            if (jar.equals(located)) continue;
            if (!referencedInMessage(msg, jar.getFileName().toString())) continue;
            ScanResult r2 = null;
            try { r2 = scanJarFull(jar); } catch (Throwable ignored) {}
            if (isUnguardedClient(r2)) {
                note("  v16: 异常消息点名 " + jar.getFileName() + "，其为纯客户端模组，隔离之（守护者 "
                    + located.getFileName() + " 保留）");
                return jar;
            }
        }
        return null;
    }

    /** v17 守护者归因：崩溃文本含「移除/不兼容」祈使句且能唯一点名他人时，隔离被点名者、保留报警的守护者（不要求被点名者是纯客户端）。 */
    /** v17b 家族连坐：directHit 取核心名 familyCore，凡双向前缀匹配的同家族姊妹包一并隔离；多家族歧义则退回保留。 */
    private static List<Path> resolveGuardianCulprit(String attributionText, Path guardian, List<Path> jars, Config cfg) {
        if (attributionText == null || attributionText.isEmpty()) return new ArrayList<Path>();
        String norm = normalizeForMatch(attributionText);
        if (!hasRemovalDirective(attributionText.toLowerCase(), norm)) {
            note("GUARDIAN_ATTRIB_MISS no-directive " + guardian.getFileName());
            return new ArrayList<Path>();
        }
        // 1) 被消息直接点名的 jar（消息含其归一化核心名）
        List<Path> direct = new ArrayList<Path>();
        for (Path jar : jars) {
            if (jar.equals(guardian)) continue;
            if (referencedInMessage(norm, jar.getFileName().toString())) direct.add(jar);
        }
        if (direct.isEmpty()) {
            note("GUARDIAN_ATTRIB_MISS no-target " + guardian.getFileName());
            return new ArrayList<Path>();
        }
        // 2) 多个 directHit 分属不同家族 → 歧义，保 guardian
        String familyCore = jarCore(direct.get(0).getFileName().toString());
        for (Path d : direct) {
            if (!jarCore(d.getFileName().toString()).equals(familyCore)) {
                note("GUARDIAN_ATTRIB_MISS ambiguous " + direct.get(0).getFileName() + " / " + d.getFileName());
                return new ArrayList<Path>();
            }
        }
        // 3) 家族连坐：同前缀（双向）姊妹包一并隔离
        List<Path> out = familyMembers(familyCore, jars, guardian, cfg);
        if (out.isEmpty()) note("GUARDIAN_ATTRIB_MISS no-family " + guardian.getFileName());
        return out;
    }

    /** v17b：以 familyCore 为前缀，收集所有同家族姊妹包（排除 guardian、白名单/L0 覆盖）。 */
    private static List<Path> familyMembers(String familyCore, List<Path> jars, Path guardian, Config cfg) {
        List<Path> out = new ArrayList<Path>();
        for (Path jar : jars) {
            if (jar.equals(guardian)) continue;
            String core = jarCore(jar.getFileName().toString());
            if (core.length() < 5) continue;
            if (isWhitelisted(jar, cfg)) continue;
            if (familyCore.startsWith(core) || core.startsWith(familyCore)) out.add(jar);
        }
        return out;
    }

    /** v17b：jar 是否在白名单（modId 或文件名），与 path A 静态预检口径一致。 */
    private static boolean isWhitelisted(Path jar, Config cfg) {
        if (cfg == null) return false;
        String name = jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (cfg.whitelist.contains(name)) return true;
        String id;
        try { id = detectModMeta(jar).modId.toLowerCase(java.util.Locale.ROOT); }
        catch (Throwable e) { return false; }
        return cfg.whitelist.contains(id);
    }

    /** v17：移除类祈使语义关键词。ASCII 走归一化文本，中文只能走原文（归一化会清掉非 a-z0-9）。 */
    private static final String[] REMOVE_HINT_ASCII = {
        "removeit", "pleaseremove", "mustberemoved", "shouldberemoved", "removethe",
        "removethis", "deleteit", "pleasedelete", "uninstall",
        "notcompatible", "incompatiblewith", "isincompatible", "donotuse", "shouldnotbeinstalled"
    };
    private static final String[] REMOVE_HINT_RAW = {
        "请移除", "请删除", "移除它", "删除它", "需要移除", "不兼容", "请卸载"
    };

    private static boolean hasRemovalDirective(String rawLower, String normalized) {
        for (String k : REMOVE_HINT_ASCII) if (normalized.contains(k)) return true;
        for (String k : REMOVE_HINT_RAW) if (rawLower.contains(k)) return true;
        return false;
    }

    /** 归一化：小写 + 去掉所有非字母数字字符，用于容错包含比对。 */
    private static String normalizeForMatch(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** 取模组文件名的「归一化核心名」：去版本/forge/分隔符，仅留 a-z0-9（如 aaa_particles-forge → aaaparticles）。 */
    private static String jarCore(String fileName) {
        return normalizeForMatch(fileName.replaceAll("(?i)\\.jar$", "")
            .replaceAll("(?i)[-_]?forge[-_]?\\d*(\\.\\d+)*", "")
            .replaceAll("(?i)[-_+]?\\d+(\\.\\d+)*", ""));
    }

    /**
     * 已归一化的异常消息是否点名了给定模组文件名（忽略大小写/分隔符/版本号做容错比对）。
     * core 过短（&lt;5）时直接放弃，避免 "core"/"lib" 之类通配误伤。
     */
    static boolean referencedInMessage(String normalizedMsg, String fileName) {
        String core = jarCore(fileName);
        if (core.length() < 5) return false;
        return normalizedMsg.contains(core);
    }

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

    /** v16：从异常链消息里提取「Mixin 配置名」（如 betterlockon.mixins.json），用于直接归属崩溃属主。 */
    private static final Pattern MIXIN_CFG_PATTERN =
        Pattern.compile("([\\w.+-]+\\.mixins?\\.json)(?::|\\b)");

    private static List<String> parseMixinConfigNames(Throwable t) {
        List<String> out = new ArrayList<String>();
        Throwable c = t;
        int n = 0;
        while (c != null && n++ < 16) {
            String msg = c.getMessage();
            if (msg != null) {
                Matcher m = MIXIN_CFG_PATTERN.matcher(msg);
                while (m.find()) {
                    String cfg = m.group(1);
                    if (!out.contains(cfg)) out.add(cfg);
                }
            }
            c = c.getCause();
        }
        return out;
    }

    /** v16b：从 logs/latest.log 尾部提取崩溃的 Mixin 配置名（如 betterlockon.mixins.json）；此类被 Forge 内部捕获后 System.exit、不抛 handleCrash、不写 crash-report，特征只残留在日志。 */
    /** 命中 *.mixins.json 配置名即归属其属主模组并隔离，与 handleCrash path 0.5 共用判据（仅真崩日志含 FAILED during APPLY 时触发）。 */
    private static List<String> parseMixinConfigNamesFromLog() {
        List<String> out = new ArrayList<String>();
        Path log = Paths.get("logs", "latest.log");
        if (!Files.isRegularFile(log)) return out;
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 600);
            int lastFail = -1;
            for (int i = start; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains("FAILED during APPLY")
                    || (l.contains("InvalidMixinException") && l.toLowerCase().contains("mixin"))
                    || l.contains("Mixin apply for mod")) {
                    Matcher m = MIXIN_CFG_PATTERN.matcher(l);
                    while (m.find()) {
                        String cfg = m.group(1);
                        if (!out.contains(cfg)) out.add(cfg);
                    }
                    if (!out.isEmpty()) lastFail = i;
                }
            }
            if (lastFail < 0) return out;
            // 零误伤闸门（v16c）：Mixin APPLY 失败不必然致命（required=false 只打日志、服务端照常起来）。
            // 失败行之后若仍出现 "Done (" 即良性、本次关服为正常停服→一律不隔离；只有之后再没起来才认定真凶（与 v12「证据不足一律保留」一致）。
            for (int i = lastFail + 1; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains("Done (") || l.contains("For help, type")) {
                    note("MIXIN_CFG_SKIP benign (server reached Done after "
                        + out.get(0) + " failure)");
                    return new ArrayList<String>();
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static Path findJarContainingEntry(List<Path> jars, String entry) {
        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                if (jf.getJarEntry(entry) != null) return jar;
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
        List<String> cmd = readPersistedLaunchArgs();
        if (cmd == null) cmd = buildCommand(args);
        else note("RESTART_ARGS 复用 " + LAUNCH_ARGS_FILE);
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

    /** 重建启动命令：JVM 参数取自 RuntimeMXBean，主入口另行还原（getInputArguments 不含 -jar/主类，旧版 hasJar 分支恒为 false）。 */
    private static List<String> buildCommand(String[] args) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaExe());
        RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
        for (String a : bean.getInputArguments()) cmd.add(a);
        // -jar 启动时 JVM 会把 classpath 设为该 jar 单条，据此判别比切 sun.java.command 更稳（不受路径空格影响）
        String cp = System.getProperty("java.class.path");
        String sep = System.getProperty("path.separator", ";");
        if (cp != null && !cp.contains(sep) && cp.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            cmd.add("-jar");
            cmd.add(cp);
        } else {
            cmd.add("-cp");
            cmd.add(cp == null ? "." : cp);
            cmd.add(mainClassName());
        }
        if (args != null) {
            for (String a : args) cmd.add(a);
        }
        return cmd;
    }

    /** 主类名取自 sun.java.command 首 token，不可用时回退核心启动器。 */
    private static String mainClassName() {
        String c = System.getProperty("sun.java.command");
        if (c != null) {
            String first = c.trim().split("\\s+")[0];
            if (!first.isEmpty() && !first.toLowerCase(Locale.ROOT).endsWith(".jar")) return first;
        }
        return "io.izzel.arclight.server.Launcher";
    }

    /** v18: boot 期落盘重启命令行，服主可手工修正（JAVA_TOOL_OPTIONS 等注入参数 getInputArguments 未必可见）。写盘失败静默。 */
    private static void persistLaunchArgs(String[] args) {
        try {
            Files.createDirectories(CLIENTCHECK_DIR);
            List<String> cmd = buildCommand(args);
            // 主入口(jar/classpath)未变则保留旧文件，不覆盖服主手工修正；换 jar 升版后自动重写
            String cp = System.getProperty("java.class.path");
            List<String> old = readPersistedLaunchArgs();
            if (old != null && cp != null && old.contains(cp)) return;
            StringBuilder sb = new StringBuilder();
            sb.append("# PRTS 自愈重启命令行：每行一个参数，# 为注释。删除本文件则自动重建。")
                .append(System.lineSeparator());
            sb.append("# 生成于 ")
                .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()))
                .append(System.lineSeparator());
            for (String c : cmd) sb.append(c).append(System.lineSeparator());
            Files.write(LAUNCH_ARGS_FILE, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    /** 读回持久化命令行；缺失/token 过少/首项不存在一律返回 null，由调用方回退自动重建。 */
    private static List<String> readPersistedLaunchArgs() {
        try {
            if (!Files.exists(LAUNCH_ARGS_FILE)) return null;
            List<String> cmd = new ArrayList<String>();
            for (String line : Files.readAllLines(LAUNCH_ARGS_FILE, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                cmd.add(t);
            }
            if (cmd.size() < 2 || !Files.exists(Paths.get(cmd.get(0)))) return null;
            // -jar 目标不存在说明文件已过期（如升版换名），判为非法让调用方重建
            for (int i = 0; i < cmd.size() - 1; i++) {
                if ("-jar".equals(cmd.get(i)) && !Files.exists(Paths.get(cmd.get(i + 1)))) return null;
            }
            return cmd;
        } catch (Throwable t) {
            return null;
        }
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

    // v18: 可选外部指纹增量 _clientcheck/custom_fingerprints.json，格式 {"fingerprints":{"包/类.class":"原因"}}。
    // 只增不覆盖内置条目（放行请用 allowlist，语义清晰且有日志）；文件缺失或解析失败一律静默跳过。
    private static void loadCustomFingerprints() {
        if (CUSTOM_FP_LOADED) return;
        CUSTOM_FP_LOADED = true;
        Path p = CLIENTCHECK_DIR.resolve("custom_fingerprints.json");
        if (!Files.exists(p)) return;
        try {
            String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Matcher blk = Pattern.compile("\"fingerprints\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL).matcher(json);
            if (!blk.find()) return;
            Matcher kv = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(blk.group(1));
            int added = 0;
            int skipped = 0;
            while (kv.find()) {
                String raw = kv.group(1).trim();
                if (raw.endsWith(".class")) raw = raw.substring(0, raw.length() - 6);
                String key = raw.replace('.', '/') + ".class";
                if (key.length() < 8 || key.startsWith("/") || KNOWN_BAD_FINGERPRINTS.containsKey(key)) {
                    skipped++;
                    continue;
                }
                String reason = kv.group(2).trim();
                KNOWN_BAD_FINGERPRINTS.put(key, (reason.isEmpty() ? "自定义指纹" : reason) + " [custom]");
                added++;
            }
            if (added > 0 || skipped > 0) {
                note("CUSTOM_FINGERPRINTS 载入 " + added + " 条，跳过 " + skipped + " 条（重复或格式非法） <- " + p.toAbsolutePath());
            }
            if (added > 0) {
                System.out.println("[PRTS] 客户端模组预检: 已载入自定义类名指纹 " + added + " 条");
            }
        } catch (Exception ignored) {}
    }

    public static final class Config {
        final Set<String> whitelist = new HashSet<String>();
        final Set<String> blacklist = new HashSet<String>();
        boolean autoQuarantine = false; // 默认关闭隔离(仅报告)；prts.yml guard.autoQuarantine=true 可开启
        int maxRestarts = 5; // 自愈重启上限，从配置读，fallback 5

        /** v18: 实际生效的配置文件绝对路径，null=未找到；用于日志消歧（新旧文件名并存时服主易改错文件）。 */
        String sourceFile = null;

        /** v12 P0-5：AMBER（有客户端代码但无确证危害证据）是否也隔离；默认 false=无罪推定（误删库代价整包起不来，历史 5 次误删源于此）。管理员可显式开启。 */
        boolean pruneHarmlessClientMods = false;

        /**
         * 严格模式（默认 false）：除默认的高置信隔离外，额外移除一切引用客户端类的模组，
         * 追求纯净服务端（贴近人工筛选集）。可能误删"带守卫但作者标为双端"的模组，公开版默认关闭，由服主自决。
         */
        boolean strictMode = false;

        /** v12 P0-4：权威参考清单（目录或文本文件，每行一个 modId/文件名）。语义=否决启发式隔离但不跳过判定，且不否决黑名单/类名指纹/mods.toml CLIENT 三类硬证据（参考集自身混有客户端模组，会打 TRUSTED_CONFLICT）。 */
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
            // v18: 官方名 allowlist.json；whitelist.json / 根目录 clientside-guard.json 为旧名兼容
            Path p = CLIENTCHECK_DIR.resolve("allowlist.json");
            if (!Files.exists(p)) p = CLIENTCHECK_DIR.resolve("whitelist.json");
            if (!Files.exists(p)) p = Paths.get("clientside-guard.json");
            if (Files.exists(p)) {
                try {
                    String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    // 文件名与字段名统一为 allowlist/denylist，whitelist/blacklist 作同义词永久兼容
                    c.whitelist.addAll(parseArray(json, "allowlist"));
                    c.whitelist.addAll(parseArray(json, "whitelist"));
                    c.blacklist.addAll(parseArray(json, "denylist"));
                    c.blacklist.addAll(parseArray(json, "blacklist"));
                    c.sourceFile = p.toAbsolutePath().toString();
                    Matcher m = Pattern.compile("\"autoQuarantine\"\\s*:\\s*(true|false)").matcher(json);
                    if (m.find()) c.autoQuarantine = Boolean.parseBoolean(m.group(1));
                    Matcher pm = Pattern.compile("\"pruneHarmlessClientMods\"\\s*:\\s*(true|false)").matcher(json);
                    if (pm.find()) c.pruneHarmlessClientMods = Boolean.parseBoolean(pm.group(1));
                    Matcher sm = Pattern.compile("\"strictMode\"\\s*:\\s*(true|false)").matcher(json);
                    if (sm.find()) c.strictMode = Boolean.parseBoolean(sm.group(1));
                    for (String src : parseArrayRaw(json, "trustedModList")) c.loadTrusted(src);
                    Matcher rm = Pattern.compile("\"maxRestarts\"\\s*:\\s*(\\d+)").matcher(json);
                    if (rm.find()) c.maxRestarts = Integer.parseInt(rm.group(1));
                } catch (IOException ignored) {}
            }
            // v10: 兼容 prts.yml guard.allowlist（尽力解析，失败忽略）
            c.whitelist.addAll(readPrtsAllowlist());
            // 补充来源：prts.yml 的 guard 段（服主在此配置开关则以它为准，否则沿用上面 json 解析结果）
            Path prtsYml = Paths.get("prts.yml");
            if (Files.exists(prtsYml)) {
                try {
                    List<String> ls = Files.readAllLines(prtsYml, StandardCharsets.UTF_8);
                    StringBuilder sb = new StringBuilder();
                    for (String l : ls) sb.append(l).append("\n");
                    String txt = sb.toString();
                    Matcher am = Pattern.compile("(?m)^\\s*autoQuarantine\\s*:\\s*(true|false)").matcher(txt);
                    if (am.find()) c.autoQuarantine = Boolean.parseBoolean(am.group(1));
                    Matcher mr = Pattern.compile("(?m)^\\s*maxRestarts\\s*:\\s*(\\d+)").matcher(txt);
                    if (mr.find()) c.maxRestarts = Integer.parseInt(mr.group(1));
                } catch (IOException ignored) {}
            }
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
            Path p = CLIENTCHECK_DIR.resolve("state.json");
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
                    // v15：正则末尾强制要求 "poisonMixin" 字段。旧版(v12 及以前)缓存没有该字段，
                    // 匹配不上即整条失效 -> 自动重扫，不会拿旧缓存漏掉中毒 mixin 判定。
                    Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\\{\\s*\"hasClient\"\\s*:\\s*(true|false),\\s*\"hasServer\"\\s*:\\s*(true|false),\\s*\"hasContent\"\\s*:\\s*(true|false),\\s*\"hasCommonMixin\"\\s*:\\s*(true|false),\\s*\"hasDistGuard\"\\s*:\\s*(true|false),\\s*\"hasKjsPlugin\"\\s*:\\s*(true|false),\\s*\"hasBroadGuard\"\\s*:\\s*(true|false),\\s*\"poisonMixin\"\\s*:\\s*(?:null|\"((?:[^\"\\\\]|\\\\.)*)\"),\\s*\"clientTargetMixin\"\\s*:\\s*(?:null|\"((?:[^\"\\\\]|\\\\.)*)\")")
                        .matcher(scBlock);
                    while (m.find()) {
                        ScanResult sr = new ScanResult();
                        sr.hasClient = Boolean.parseBoolean(m.group(2));
                        sr.hasServer = Boolean.parseBoolean(m.group(3));
                        sr.hasContent = Boolean.parseBoolean(m.group(4));
                        sr.hasCommonMixin = Boolean.parseBoolean(m.group(5));
                        sr.hasDistGuard = Boolean.parseBoolean(m.group(6));
                        sr.hasKjsPlugin = Boolean.parseBoolean(m.group(7));
                        sr.hasBroadGuard = Boolean.parseBoolean(m.group(8));
                        sr.poisonMixin = m.group(9) == null ? null : unquote(m.group(9));
                sr.clientTargetMixin = m.group(10) == null ? null : unquote(m.group(10));
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
                        .append(", \"hasKjsPlugin\": ").append(r.hasKjsPlugin)
                        .append(", \"hasBroadGuard\": ").append(r.hasBroadGuard)
                        .append(", \"poisonMixin\": ").append(r.poisonMixin == null ? "null" : quote(r.poisonMixin))
                        .append(", \"clientTargetMixin\": ").append(r.clientTargetMixin == null ? "null" : quote(r.clientTargetMixin))
                        .append(" }");
                }
                sb.append("\n  }\n}\n");
                try { Files.createDirectories(CLIENTCHECK_DIR); } catch (IOException ignored3) {}
                Files.write(CLIENTCHECK_DIR.resolve("state.json"), sb.toString().getBytes(StandardCharsets.UTF_8),
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

        /** quote() 的逆运算：只处理 \" 与 \\ 两种转义（quote 也只产生这两种）。 */
        private static String unquote(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) c = s.charAt(++i);
                sb.append(c);
            }
            return sb.toString();
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
            Files.createDirectories(CLIENTCHECK_DIR);
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
            String line = "[" + ts + "] " + s + System.lineSeparator();
            Files.write(PRECHECK_LOG, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            // 自愈/崩溃事件额外写入持久日志（precheck log 每次启动会被清空重建）
            if (s.startsWith("SELFHEAL") || s.startsWith("SHUTDOWN_HEAL") || s.startsWith("CRASH")
                || s.startsWith("MIXIN_CFG") || s.startsWith("GUARDIAN_ATTRIB")
                || s.startsWith("QUARANTINE_FAIL") || s.startsWith("CRASHREPORT")) {
                Files.write(CLIENTCHECK_DIR.resolve("isolation.log"), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) {}
    }

    // ===================== 旧文件迁移（v17b：收敛到 _clientcheck/ + 隔离区去双层） =====================

    /** 启动时一次性迁移：根目录旧 guard 文件 → _clientcheck/，旧隔离区 _quarantine[/clientside] → _disabled_mods/。失败忽略。 */
    private static void migrateLegacyFiles() {
        try { Files.createDirectories(CLIENTCHECK_DIR); } catch (IOException ignored) {}
        moveRename(Paths.get("_guard_state.json"), CLIENTCHECK_DIR.resolve("state.json"));
        moveRename(Paths.get("_guard_heal.log"), CLIENTCHECK_DIR.resolve("isolation.log"));
        moveRename(Paths.get("_guard_precheck.log"), CLIENTCHECK_DIR.resolve("precheck.log"));
        // v18: 配置文件统一为 _clientcheck/allowlist.json（与字段名 allowlist 对齐）
        moveRename(Paths.get("clientside-guard.json"), CLIENTCHECK_DIR.resolve("allowlist.json"));
        moveRename(CLIENTCHECK_DIR.resolve("whitelist.json"), CLIENTCHECK_DIR.resolve("allowlist.json"));
        // 隔离区改名（v17c）：旧 _quarantine/clientside/* → _quarantine/，再 _quarantine/* → _disabled_mods/
        Path oldQ = Paths.get("_quarantine");
        if (Files.isDirectory(oldQ)) {
            try {
                // 1) 拍平双层 clientside 子目录到 _quarantine/ 根
                Path oldQc = Paths.get("_quarantine", "clientside");
                if (Files.isDirectory(oldQc)) {
                    try {
                        List<Path> oldc = new ArrayList<Path>();
                        collectJars(oldQc, oldc);
                        for (Path f : oldc) {
                            try { Files.move(f, oldQ.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING); }
                            catch (IOException ignored2) {}
                        }
                        try { Files.deleteIfExists(oldQc); } catch (IOException ignored2) {}
                    } catch (IOException ignored2) {}
                }
                // 2) 整目录 jar 迁到新隔离区
                Files.createDirectories(QUARANTINE_DIR);
                List<Path> old = new ArrayList<Path>();
                collectJars(oldQ, old);
                for (Path f : old) {
                    try { Files.move(f, QUARANTINE_DIR.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING); }
                    catch (IOException ignored2) {}
                }
                // 3) 删掉旧目录残余
                deleteDirRecursive(oldQ);
            } catch (IOException ignored2) {}
        }
    }

    /** 递归删除目录（迁移后清理旧隔离区用），失败忽略。 */
    private static void deleteDirRecursive(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try {
            java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
            try {
                for (Path child : ds) {
                    if (Files.isDirectory(child)) deleteDirRecursive(child);
                    else Files.deleteIfExists(child);
                }
            } finally {
                ds.close();
            }
        } catch (IOException ignored) {}
        try { Files.deleteIfExists(dir); } catch (IOException ignored) {}
    }

    // v18: 目标已存在则不迁移——旧名文件回灌覆盖新名会丢掉现行配置/状态，新名永远更权威
    private static void moveRename(Path from, Path to) {
        if (!Files.exists(from) || Files.exists(to)) return;
        try { Files.move(from, to); }
        catch (IOException ignored) {}
    }

    /** 无服务端信号且无分服务端守卫的裸客户端模组（疑似纯客户端），用于守护者归因判定。 */
    private static boolean isUnguardedClient(ScanResult r) {
        return r != null && r.hasClient && !r.hasServer && !r.hasDistGuard && !r.hasBroadGuard;
    }

    /** 收集 jar 根目录下的所有 mixin 配置文件名（mixin 配置一律在 jar 根）。 */
    private static List<String> collectMixinConfigs(JarFile jf) {
        List<String> cfgs = new ArrayList<String>();
        Enumeration<JarEntry> en = jf.entries();
        while (en.hasMoreElements()) {
            JarEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName();
            if (n.indexOf('/') >= 0) continue;
            String ln = n.toLowerCase(Locale.ROOT);
            if (ln.endsWith(".json") && ln.contains("mixin")) cfgs.add(n);
        }
        return cfgs;
    }

    /** 取 crash-reports 中本次启动后(>=sinceMillis)最新的一份 .txt，无则返回 null。 */
    private static Path newestCrashReport(long sinceMillis) {
        Path dir = Paths.get("crash-reports");
        if (!Files.isDirectory(dir)) return null;
        Path newest = null;
        long best = 0L;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".txt")) continue;
                long m = Files.getLastModifiedTime(p).toMillis();
                if (m > best) { best = m; newest = p; }
            }
        } catch (IOException ignored) { return null; }
        if (newest == null || best < sinceMillis) return null;
        return newest;
    }
}
