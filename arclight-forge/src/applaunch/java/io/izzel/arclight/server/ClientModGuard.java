package io.izzel.arclight.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 启动前扫描 mods 目录，隔离会导致服务端崩溃的客户端专用模组（参考 IMBlocker 案例），并运行期自愈。
 * v22 内部重构：解析/判定/自愈/配置已拆至 GuardPaths/GuardMarkers/ModJarParser/GuardConfig/CrashSelfHeal（行为不变）。
 * 版本迭代史与判据实证见 docs/clientmodguard-iteration-log.md；代码注释一律 ≤2 行。
 */
public final class ClientModGuard {

    private static volatile boolean SELF_HEALED = false;
    private static String[] LAUNCH_ARGS = new String[0];
    private static long BOOT_TIME = System.currentTimeMillis();

    private ClientModGuard() {}

    public static void run() {
        run(new String[0]);
    }

    public static void run(String[] args) {
        if (args != null) LAUNCH_ARGS = args;
        BOOT_TIME = System.currentTimeMillis();
        GuardConfig.ensureGuardConfig(); // 先于 load，保证本次启动即生成/读到 guard.yml
        GuardConfig.Config cfg = GuardConfig.Config.load();
        GuardPaths.MAX_RESTART = cfg.maxRestarts > 0 ? cfg.maxRestarts : 5;
        CrashSelfHeal.persistLaunchArgs(LAUNCH_ARGS);
        if (cfg.autoQuarantine) {
            // 总开关：true 才运行整套自检（预检+隔离+自愈）；false 整套关闭，不扫描不隔离
            try {
                scan();
            } catch (Throwable t) {
                System.err.println("[PRTS] 客户端模组预检异常（已跳过，不影响启动）: " + t);
                GuardConfig.note("EXCEPTION " + t);
            }
        } else {
            System.out.println("[PRTS] 客户端模组自检已关闭 (autoQuarantine=false)，整套预检/隔离/自愈均不启用");
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
                        if (CrashSelfHeal.isClientClassMissing(e)) {
                            GuardConfig.note("UNCAUGHT_CLIENT_FAILURE thread=" + t.getName() + " " + CrashSelfHeal.summarize(e));
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

        Path dir = GuardPaths.MODS_DIR != null && Files.isDirectory(GuardPaths.MODS_DIR)
            ? GuardPaths.MODS_DIR : Paths.get(System.getProperty("fml.modsDir", "mods"));
        List<Path> jars = new ArrayList<Path>();
        try { ModJarParser.collectJars(dir, jars); } catch (IOException ignored) {}
        GuardConfig.Config guardCfg = GuardConfig.Config.load();
        if (!guardCfg.autoQuarantine) return; // 统一总开关：autoQuarantine=false 时同步关闭自愈，崩溃不隔离不重启

        Map<String, Object[]> offenders = new LinkedHashMap<String, Object[]>();

        // 路径 A（v10）：崩溃报告里明确写着"某模组因缺失客户端类而加载失败"。
        List<String[]> failures = CrashSelfHeal.parseCrashReportClientFailures(BOOT_TIME);
        for (String[] pr : failures) {
            Path jar = CrashSelfHeal.findJarByModId(jars, pr[0]);
            if (jar != null) offenders.put(pr[0], new Object[]{jar, "runtime: " + pr[1]});
        }
        if (!offenders.isEmpty()) GuardConfig.note("SHUTDOWN_HEAL detected clientFailures=" + failures.size());

        // 路径 B（v15）：崩溃报告只有"X has class loading errors"，无客户端类名，但 Forge 已认定该模组搞挂服务端。
        // 仅当该 jar 确有客户端代码特征才隔离（零误删底线），否则只记录不动手。
        if (offenders.isEmpty()) {
            List<String[]> modFails = CrashSelfHeal.parseCrashReportModFailures(BOOT_TIME);
            for (String[] pr : modFails) {
                Path jar = CrashSelfHeal.findJarByModIdOrName(jars, pr[0]);
                if (jar == null) { GuardConfig.note("SHUTDOWN_HEAL_SKIP no-jar " + pr[0]); continue; }

                // v17 守护者归因：先判失败是否由作者点名他人并要求移除；命中则隔离被点名真凶（含同家族姊妹包）、保住守护者，不新增隔离对象。
                List<Path> culprits = CrashSelfHeal.resolveGuardianCulprit(pr.length > 2 ? pr[2] : "", jar, jars, guardCfg);
                if (!culprits.isEmpty()) {
                    StringBuilder names = new StringBuilder();
                    for (Path c : culprits) {
                        String cid;
                        try { cid = ModJarParser.detectModMeta(c).modId; }
                        catch (Throwable e) { cid = c.getFileName().toString(); }
                        offenders.put(cid, new Object[]{c, "runtime: 被 " + jar.getFileName()
                            + " 检测为服务端不兼容并要求移除（守护者归因）"});
                        if (names.length() > 0) names.append(", ");
                        names.append(c.getFileName().toString());
                    }
                    System.err.println("[PRTS] " + jar.getFileName() + " 点名以下模组为服务端不兼容并要求移除，已按家族隔离被点名者，保留报告者：" + names);
                    GuardConfig.note("GUARDIAN_ATTRIB " + jar.getFileName() + " -> " + names);
                    continue;
                }

                ModJarParser.ScanResult r = null;
                try { r = ModJarParser.scanJarFull(jar); } catch (Throwable ignored) {}
                if (r == null || !r.hasClient) {
                    System.err.println("[PRTS] Forge 报告模组加载失败: " + pr[0]
                        + "，但该模组无客户端代码特征，不予自动隔离（可能是真实故障，请查崩溃报告）。");
                    GuardConfig.note("SHUTDOWN_HEAL_SKIP no-client-code " + pr[0]);
                    continue;
                }
                String modId;
                try { modId = ModJarParser.detectModMeta(jar).modId; } catch (Throwable e) { modId = jar.getFileName().toString(); }
                offenders.put(modId, new Object[]{jar, "runtime: " + pr[1]});
            }
            if (!offenders.isEmpty()) GuardConfig.note("SHUTDOWN_HEAL detected modLoadFailures=" + offenders.size());
        }

        // 路径 C（v16b）：崩溃特征只在日志里（Forge 吞异常并 System.exit，无 crash-report）。
        // 扫 logs/latest.log 尾部，命中"*.mixins.json 在专用服注入失败"即归属属主并隔离；仅真崩时触发。
        if (offenders.isEmpty()) {
            for (String cfg : CrashSelfHeal.parseMixinConfigNamesFromLog()) {
                Path jar = CrashSelfHeal.findJarContainingEntry(jars, cfg);
                if (jar == null) jar = CrashSelfHeal.findJarByModIdOrName(jars, cfg.replaceFirst("\\.mixins?\\.json$", ""));
                if (jar == null) continue;
                String mid;
                try { mid = ModJarParser.detectModMeta(jar).modId; } catch (Throwable e) { mid = jar.getFileName().toString(); }
                offenders.put(mid, new Object[]{jar, "runtime: Mixin 配置 " + cfg
                    + " 在专用服注入失败（客户端类缺失，已自动隔离）"});
                GuardConfig.note("MIXIN_CFG_SELFHEAL " + cfg + " -> " + jar.getFileName());
                break;
            }
        }

        if (offenders.isEmpty()) {
            if (!failures.isEmpty()) GuardConfig.note("SHUTDOWN_HEAL cannot locate jars for failures");
            return;
        }
        if (!quarantineOffenders(offenders)) return;

        int retry = CrashSelfHeal.currentRetry() + 1;
        if (retry > GuardPaths.MAX_RESTART) {
            System.err.println("[PRTS] 自愈重启次数已达上限(" + GuardPaths.MAX_RESTART + ")，停止自动重启。请检查并清理 mods/ 中的客户端模组。");
            GuardConfig.note("SHUTDOWN_HEAL retry limit reached");
            return;
        }
        List<String> cmd = CrashSelfHeal.buildCommand(LAUNCH_ARGS);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        pb.environment().put(GuardPaths.RETRY_ENV, String.valueOf(retry));
        try {
            pb.start();
            System.out.println("[PRTS] 自愈完成，已拉起新服务端进程（第 " + retry + " 次自动重启）。");
            GuardConfig.note("SHUTDOWN_HEAL respawned retry=" + retry);
        } catch (IOException e) {
            System.err.println("[PRTS] 自愈重启失败（无法启动新 JVM）: " + e);
            GuardConfig.note("SHUTDOWN_HEAL respawn failed " + e);
        }
    }

    /** 隔离 offenders 并更新状态文件；全部成功返回 true。 */
    private static boolean quarantineOffenders(Map<String, Object[]> offenders) {
        GuardConfig.GuardState state = GuardConfig.GuardState.load();
        for (Map.Entry<String, Object[]> e : offenders.entrySet()) {
            String modId = e.getKey();
            Path jar = (Path) e.getValue()[0];
            String reason = (String) e.getValue()[1];
            String digest = CrashSelfHeal.sha1(jar); // 隔离前取摘要（隔离后文件已移走）
            try {
                runtimeQuarantine(jar);
            } catch (Throwable qe) {
                System.err.println("[PRTS] 自愈隔离模组失败: " + qe);
                GuardConfig.note("QUARANTINE_FAIL " + jar + " " + qe);
                return false;
            }
            state.quarantined.put(modId, new GuardConfig.GuardState.Info(digest, reason, System.currentTimeMillis()));
            int fails = 0;
            GuardConfig.GuardState.Info fi = state.insistedFailed.get(modId);
            if (fi != null) fails = (int) fi.at; // insistedFailed 的 at 字段复用存储连崩计数
            fails++;
            state.insistedFailed.put(modId, new GuardConfig.GuardState.Info(digest, "insisted crash x" + fails, fails));
            System.out.println("[PRTS] 自愈：已隔离导致崩溃的客户端模组 " + jar.getFileName() + " (modId=" + modId + ")");
            GuardConfig.note("SELFHEAL " + jar.getFileName() + " modId=" + modId + " " + reason + " insistedFails=" + fails);
            if (fails >= 2) {
                System.err.println("[PRTS] 警告：模组 '" + modId + "' 已被多次隔离（疑似必须为客户端）。后续每次启动都会自动隔离它。");
                System.err.println("[PRTS] 如需强制保留，请在 _clientcheck/allowlist.json 的 allowlist 加入 \"" + modId + "\"（或 guard.yml allowlist）；否则请将其移出 mods/。");
            }
        }
        state.save();
        return true;
    }

    /** 运行时自愈入口：捕获 Main_Forge.main 抛出的"缺失客户端类/模组加载失败"异常，定位并隔离 offending mod 后重启。 */
    public static synchronized void handleCrash(Throwable t, String[] args) {
        if (!GuardConfig.Config.load().autoQuarantine) return; // 统一总开关：autoQuarantine=false 时预检与自愈同步关闭，崩溃交还 JVM 默认行为
        boolean direct = CrashSelfHeal.isClientClassMissing(t);
        boolean modLoadFail = CrashSelfHeal.isModLoadingFailed(t);
        if (!direct && !modLoadFail) return; // 无关崩溃，交还调用方正常抛出
        GuardConfig.note("CRASH " + (direct ? "CLIENT_DIRECT" : "MOD_LOADING_FAILED") + " " + CrashSelfHeal.summarize(t));

        Path dir = GuardPaths.MODS_DIR != null && Files.isDirectory(GuardPaths.MODS_DIR)
            ? GuardPaths.MODS_DIR : Paths.get(System.getProperty("fml.modsDir", "mods"));
        List<Path> jars = new ArrayList<Path>();
        try { ModJarParser.collectJars(dir, jars); } catch (IOException ignored) {}

        // offenders: modId -> {jar, reason}
        Map<String, Object[]> offenders = new LinkedHashMap<String, Object[]>();

            // 路径0.5（v16，最高优先）：Mixin APPLY 崩溃消息直接带配置名（如 betterlockon.mixins.json:...FAILED during APPLY），配置名即属主模组，精度最高。
            // 仅真崩（消息含 mixin 配置名）时触发，正常运行模组（如大量指向 EpicFight 客户端类的 CombatEvolution/tcrcore）从不触发，零误伤。
        if (offenders.isEmpty()) {
            for (String cfg : CrashSelfHeal.parseMixinConfigNames(t)) {
                Path jar = CrashSelfHeal.findJarContainingEntry(jars, cfg);
                if (jar == null) jar = CrashSelfHeal.findJarByModIdOrName(jars, cfg.replaceFirst("\\.mixins?\\.json$", ""));
                if (jar == null) continue;
                String mid;
                try { mid = ModJarParser.detectModMeta(jar).modId; } catch (Throwable e) { mid = jar.getFileName().toString(); }
                offenders.put(mid, new Object[]{jar, "runtime: Mixin 配置 " + cfg
                    + " 在专用服注入失败（客户端类缺失，已自动隔离）"});
                GuardConfig.note("MIXIN_CFG_SELFHEAL " + cfg + " -> " + jar.getFileName());
                break;
            }
        }

        // 路径1: 异常链本身含客户端类名（NoClassDefFoundError 直达 main）
        if (direct) {
            Path mod = CrashSelfHeal.locateOffendingMod(t);
            if (mod != null) {
                String modId;
                try { modId = ModJarParser.detectModMeta(mod).modId; } catch (Throwable e) { modId = mod.getFileName().toString(); }
                // v16：定位到的模组可能只是「守护者」（自身双端安全，只是检测到别的模组缺客户端类而报错，
                // 如 tcrcore 点名 aaa_particles）。此时应隔离异常消息里点名的真凶，保留守护者。
                Path real = CrashSelfHeal.resolveRealCulprit(t, mod, jars);
                if (real != null && !real.equals(mod)) {
                    String realId;
                    try { realId = ModJarParser.detectModMeta(real).modId; } catch (Throwable e) { realId = real.getFileName().toString(); }
                    offenders.put(realId, new Object[]{real, "runtime: v16 隔离真凶 " + real.getFileName()
                        + "（守护者 " + mod.getFileName() + " 保留）"});
                } else {
                    offenders.put(modId, new Object[]{mod, "runtime: " + CrashSelfHeal.missingClassName(t)});
                }
            }
        }
            // 路径1.5（v14）: Forge 的 LoadingFailedException 直接在消息里【点名】失败模组（"X has class loading errors"），精度最高且不依赖 crash-report 落盘。
            // 为守零误删底线：仅当该 jar 确含客户端代码特征才隔离，否则只告警交还正常崩溃。
        if (offenders.isEmpty() && modLoadFail) {
            for (String name : CrashSelfHeal.parseLoadingFailureNames(t)) {
                Path jar = CrashSelfHeal.findJarByModIdOrName(jars, name);
                if (jar == null) continue;
                ModJarParser.ScanResult r = null;
                try { r = ModJarParser.scanJarFull(jar); } catch (Throwable ignored) {}
                if (r == null || !r.hasClient) {
                    System.err.println("[PRTS] Forge 报告模组加载失败: " + name
                        + "，但该模组无客户端代码特征，不予自动隔离（可能是真实故障，请查日志）。");
                    GuardConfig.note("LOADFAIL_SKIP " + name);
                    continue;
                }
                String modId;
                try { modId = ModJarParser.detectModMeta(jar).modId; } catch (Throwable e) { modId = jar.getFileName().toString(); }
                offenders.put(modId, new Object[]{jar, "runtime: Forge 报告加载失败(" + name + ")"});
            }
        }
        // 路径2: FML 吞掉真实异常只抛 "Mod Loading has failed" → 解析最新 crash report
        if (offenders.isEmpty()) {
            List<String[]> parsed = CrashSelfHeal.parseCrashReportClientFailures(BOOT_TIME);
            for (String[] pr : parsed) {
                Path jar = CrashSelfHeal.findJarByModId(jars, pr[0]);
                if (jar != null) offenders.put(pr[0], new Object[]{jar, "runtime: " + pr[1]});
            }
        }
        if (offenders.isEmpty()) {
            if (direct) {
                System.err.println("[PRTS] 检测到客户端类缺失导致崩溃，但无法定位具体模组。");
                System.err.println("[PRTS] 请检查 _guard_precheck.log 或手动将疑似客户端模组移出 mods/ 目录。");
                GuardConfig.note("CRASH_UNLOCATED " + CrashSelfHeal.summarize(t));
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
        CrashSelfHeal.restart(args);
    }

    /** 子线程感知运行时自愈：兜底"子线程懒加载客户端类失败"盲区（主线程 try/catch 与 shutdown hook 都抓不到）。 */
    public static synchronized void onUncaughtClientFailure(Throwable t) {
        if (SELF_HEALED) return; // handleCrash 已处理 / 已重启，避免重入与级联重复隔离
        if (!GuardConfig.Config.load().autoQuarantine) return; // 统一总开关：autoQuarantine=false 时同步关闭自愈
        Path mod = CrashSelfHeal.locateOffendingMod(t);
        if (mod == null) {
            GuardConfig.note("UNCAUGHT_UNLOCATED " + CrashSelfHeal.summarize(t));
            System.exit(1);
            return;
        }
        Map<String, Object[]> offenders = new LinkedHashMap<String, Object[]>();
        String modId;
        try { modId = ModJarParser.detectModMeta(mod).modId; } catch (Throwable e) { modId = mod.getFileName().toString(); }
        offenders.put(modId, new Object[]{mod, "runtime-uncaught: " + CrashSelfHeal.missingClassName(t)});
        if (!quarantineOffenders(offenders)) { System.exit(1); return; }
        SELF_HEALED = true; // 防止 shutdown hook 重复处理
        System.out.println("[PRTS] 自愈（子线程捕获）完成（隔离 " + offenders.size()
            + " 个模组），自动重启服务端...");
        GuardConfig.note("UNCAUGHT_SELFHEAL " + mod.getFileName() + " modId=" + modId);
        CrashSelfHeal.restart(LAUNCH_ARGS);
    }

    // ===================== 预扫描 =====================

    private static void scan() throws IOException {
        GuardConfig.migrateLegacyFiles();
        String prop = System.getProperty("fml.modsDir");
        GuardPaths.MODS_DIR = prop != null ? Paths.get(prop) : Paths.get("mods");
        if (!Files.isDirectory(GuardPaths.MODS_DIR)) return;

        GuardConfig.GuardState state = GuardConfig.GuardState.load();
        GuardConfig.Config cfg = GuardConfig.Config.load();

        // v10: 清理上轮运行时改名遗留的 .prts-quarantined（同目录改名兜底，本次真正移走）
        cleanupPending();

        List<Path> jars = new ArrayList<Path>();
        ModJarParser.collectJars(GuardPaths.MODS_DIR, jars);
        try { Files.deleteIfExists(GuardPaths.PRECHECK_LOG); } catch (IOException ignored) {}
        GuardConfig.note("START modsDir=" + GuardPaths.MODS_DIR.toAbsolutePath() + " jars=" + jars.size()
            + " autoQuarantine=" + cfg.autoQuarantine + " prune=" + cfg.pruneHarmlessClientMods);
        if (cfg.sourceFile != null) GuardConfig.note("CONFIG " + cfg.sourceFile);
        GuardConfig.loadCustomFingerprints();
        if (!cfg.trustedSources.isEmpty()) {
            System.out.println("[PRTS] 客户端模组预检: 已载入权威参考清单 " + String.join(", ", cfg.trustedSources)
                + "（合计 " + cfg.trustedIds.size() + " 个 modId / " + cfg.trustedNames.size() + " 个文件名）");
            GuardConfig.note("TRUSTED_SOURCES " + String.join(", ", cfg.trustedSources));
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
            String id = ModJarParser.detectModMeta(jar).modId;
            state.quarantined.put(id, new GuardConfig.GuardState.Info(CrashSelfHeal.sha1(jar), "prescan", System.currentTimeMillis()));
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
                GuardConfig.note("  BROKEN_DEP " + s);
            }
        }
        if (!d.restored.isEmpty()) {
            System.out.println("[PRTS] 曾被隔离、已被用户加回的模组（尊重用户选择，跳过预隔离）: " + String.join(", ", d.restored));
            for (String s : d.restored) GuardConfig.note("  RESTORED " + s);
        }
        if (!d.trustedConflict.isEmpty()) {
            System.err.println("[PRTS] 警告：以下模组虽在权威参考清单中，但命中了硬证据仍被隔离——");
            System.err.println("[PRTS]       说明该参考清单自身混有客户端模组，建议人工复核清单本身：");
            for (String s : d.trustedConflict) {
                System.err.println("[PRTS]   - " + s);
                GuardConfig.note("  TRUSTED_CONFLICT " + s);
            }
        }
        if (!d.amber.isEmpty()) {
            System.out.println("[PRTS] 客户端模组预检: " + d.amber.size()
                + " 个模组含客户端代码但无确证危害证据，已【保留】并列入观察名单（明细见 _guard_precheck.log 的 AMBER 行）");
            if (!cfg.pruneHarmlessClientMods) {
                System.out.println("[PRTS]       如需一并清理，请在 _clientcheck/allowlist.json 设 \"pruneHarmlessClientMods\": true");
            }
            for (String s : d.amber) GuardConfig.note("  AMBER " + s);
        }
        if (!cfg.autoQuarantine && !d.reported.isEmpty()) {
            System.out.println("[PRTS] autoQuarantine=false，以下仅报告未隔离: " + String.join(", ", d.reported));
        }
        if (moved) {
            System.out.println("[PRTS] 已将疑似客户端模组隔离至 " + GuardPaths.QUARANTINE_DIR.toAbsolutePath());
            System.out.println("[PRTS] 若系误判，请移回 mods 并在 _clientcheck/whitelist.json 的 allowlist 加入其 modId/文件名");
        } else if (d.toQuarantine.isEmpty() && d.reported.isEmpty()) {
            System.out.println("[PRTS] 客户端模组预检：未发现疑似客户端专用模组");
        }
        System.out.println("[PRTS] 客户端模组预检结束: 隔离 " + d.toQuarantine.size()
            + " 个 / 保留 " + d.keepCount + " 个，继续启动服务端...");
        GuardConfig.note("DONE quarantined=" + d.toQuarantine.size() + " kept=" + d.keepCount
            + " fingerprint=" + d.byFingerprint.size() + " poisonMixin=" + d.byPoisonMixin.size()
            + " clientTargetMixin=" + d.byClientTargetMixin.size()
            + " chained=" + d.chained.size()
            + " reported=" + d.reported.size() + " amber=" + d.amber.size()
            + " trustedConflict=" + d.trustedConflict.size()
            + (moved ? " (moved)" : (d.toQuarantine.isEmpty() && d.reported.isEmpty() ? " (none)" : " (report-only)")));
        for (String s : d.byFingerprint) GuardConfig.note("  FINGERPRINT " + s);
        for (String s : d.byPoisonMixin) GuardConfig.note("  POISON_MIXIN " + s);
        for (String s : d.byClientTargetMixin) GuardConfig.note("  CLIENT_TARGET_MIXIN(观察,不隔离) " + s);
        for (Path p : d.toQuarantine) GuardConfig.note("  QUARANTINE " + p.getFileName());
        for (String s : d.chained) GuardConfig.note("  CHAINED " + s);
        for (String s : d.keptByDep) GuardConfig.note("  KEPT_BY_DEP " + s);
        state.save(); // v10c: 始终落盘（含 scan_cache），即便未隔离也缓存扫描结果供下次启动命中
    }

    /** 把上次运行时改名兜底的 .prts-quarantined 真正移入隔离区。 */
    private static void cleanupPending() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(GuardPaths.MODS_DIR)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.toLowerCase(Locale.ROOT).endsWith(GuardPaths.PENDING_SUFFIX)) {
                    Path target = GuardPaths.QUARANTINE_DIR.resolve(name.substring(0, name.length() - GuardPaths.PENDING_SUFFIX.length()));
                    Files.createDirectories(target.getParent());
                    if (Files.exists(target)) Files.delete(target);
                    try {
                        Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ignored) {
                        // 仍占用则留到下轮
                    }
                    GuardConfig.note("  PENDING_MOVE " + p.getFileName());
                }
            }
        } catch (IOException ignored) {}
    }

    /** 判定但不移动，供测试与真实执行共用。 */
    public static Decision decide(List<Path> jars, GuardConfig.Config cfg, GuardConfig.GuardState state) {
        Decision d = new Decision();

        int total = jars.size();
        long t0 = System.currentTimeMillis();
        System.out.println("[PRTS] 客户端模组预检: 开始扫描 " + total + " 个模组 jar（大整合包约需数秒~数十秒，请勿中断）...");
        Map<Path, ModJarParser.ModMeta> meta = new LinkedHashMap<Path, ModJarParser.ModMeta>();
        Map<Path, ModJarParser.ScanResult> result = new LinkedHashMap<Path, ModJarParser.ScanResult>();
        int done = 0;
        int cacheHits = 0;
        Set<String> liveKeys = new HashSet<String>();
        for (Path jar : jars) {
            String fileName = jar.getFileName().toString();
            if (!fileName.toLowerCase().endsWith(".jar")) continue;
            meta.put(jar, ModJarParser.detectModMeta(jar));
            // v10c: 扫描结果缓存——jar 内容(文件名:大小:修改时间)未变则复用，跳过全量字节扫描
            String key = ModJarParser.cacheKey(jar);
            liveKeys.add(key);
            ModJarParser.ScanResult cached = state.scanCache.get(key);
            if (cached != null) {
                result.put(jar, cached);
                cacheHits++;
            } else {
                result.put(jar, ModJarParser.scanJarFull(jar));
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
        for (Map.Entry<Path, ModJarParser.ModMeta> e : meta.entrySet()) {
            deps.put(e.getValue().modId, e.getValue().dependencies);
        }

        for (Path jar : jars) {
            String fileName = jar.getFileName().toString().toLowerCase();
            if (!fileName.endsWith(".jar")) continue;
            ModJarParser.ModMeta m = meta.get(jar);
            ModJarParser.ScanResult r = result.get(jar);
            if (GuardMarkers.CORE_MODIDS.contains(m.modId)) {
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
                GuardConfig.GuardState.Info fi = state.insistedFailed.get(id);
                if (fi != null && fi.at >= 2) {
                    // 连崩告警：shutdown hook 里的 System.err 会被 log4j 关闭吞掉，故在预扫描阶段（控制台可见）重复告警
                    System.err.println("[PRTS] 警告：模组 '" + id + "' (" + fn + ") 曾连续 " + fi.at + " 次因缺失客户端类崩溃后被自动隔离，现已被加回。");
                    System.err.println("[PRTS]       它几乎可以确定是客户端专用模组。若坚持保留请加入 _clientcheck/allowlist.json 的 allowlist；否则请将其移出 mods/，避免反复崩溃重启。");
                    GuardConfig.note("INSISTED_WARN " + fn + " modId=" + id + " fails=" + fi.at);
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

            String fpReason = ModJarParser.matchFingerprint(jar);
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
            // BUILTIN_SAFE 豁免：静态常量池检测看不到运行时 dist 分支保护（如 create_hypertube），内置已核实安全集优先
            if (r != null && r.poisonMixin != null && !inWhite && !GuardMarkers.BUILTIN_SAFE.contains(id)) {
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
            } else if (inWhite || GuardMarkers.BUILTIN_SAFE.contains(id) || envServer) {
                v = Verdict.KEEP;
                src = inWhite ? "L0/allowlist"
                    : (envServer ? "L1/declared-" + m.environment.toLowerCase(Locale.ROOT) : "L0/builtin-safe");
            } else if (envClient || cso) {
                // L1 硬证据：模组自声明 CLIENT 或根级 clientSideOnly=true（Forge 专用服会跳过），复刻 Forge 行为。
                v = cfg.autoQuarantine ? Verdict.QUARANTINE : Verdict.REPORT;
                src = "L1/declared-client" + (cso && !envClient ? "[clientSideOnly]" : "");
                if (trusted && v == Verdict.QUARANTINE)
                    d.trustedConflict.add(fn + " [mods.toml 自声明 " + (envClient ? "CLIENT" : "clientSideOnly=true") + "]");
            } else if (ModJarParser.isUnguardedClient(r)) {
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
            ModJarParser.ModMeta qm = meta.get(q);
            if (qm != null) quarantinedIds.put(qm.modId, q);
        }
        boolean changed = true;
        int rounds = 0;
        while (changed && rounds++ < 16) {
            changed = false;
            for (Map.Entry<Path, ModJarParser.ModMeta> e : meta.entrySet()) {
                Path jar = e.getKey();
                ModJarParser.ModMeta m = e.getValue();
                if (d.toQuarantine.contains(jar)) continue;
                for (String dep : m.dependencies) {
                    Path depJar = quarantinedIds.get(dep);
                    if (depJar == null) continue;
                    ModJarParser.ScanResult r = result.get(jar);
                    boolean strong = GuardMarkers.CORE_MODIDS.contains(m.modId) || GuardMarkers.BUILTIN_SAFE.contains(m.modId)
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
    private static void sig(String fn, String id, ModJarParser.ModMeta m, ModJarParser.ScanResult r, boolean trusted,
                            String verdict, String src) {
        GuardConfig.note("  SIG " + fn + " id=" + id
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

    private static boolean isRequiredByKept(String targetId, Map<String, Set<String>> deps,
                                            Map<Path, ModJarParser.ModMeta> meta, Map<Path, ModJarParser.ScanResult> result, GuardConfig.Config cfg) {
        for (Map.Entry<Path, ModJarParser.ModMeta> e : meta.entrySet()) {
            ModJarParser.ModMeta m = e.getValue();
            if (m.modId.equals(targetId)) continue;
            if (!deps.getOrDefault(m.modId, Collections.<String>emptySet()).contains(targetId)) continue;
            ModJarParser.ScanResult r = result.get(e.getKey());
            boolean inWhite = cfg.whitelist.contains(m.modId);
            boolean safe = GuardMarkers.BUILTIN_SAFE.contains(m.modId) || inWhite
                || "SERVER".equalsIgnoreCase(m.environment) || "BOTH".equalsIgnoreCase(m.environment);
            if (safe || (r != null && (r.hasServer || r.hasContent || r.hasDistGuard || r.hasKjsPlugin))) return true;
        }
        return false;
    }

    /** v12 原子隔离：优先 ATOMIC_MOVE，失败则「复制→校验哈希→删源→确认消失」；删源失败必须回滚，否则 mods/ 与隔离区同名共存。 */
    private static void quarantine(Path jar) throws IOException {
        Path rel = GuardPaths.MODS_DIR.relativize(jar);
        Path target = GuardPaths.QUARANTINE_DIR.resolve(rel);
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);
        try {
            Files.move(jar, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException atomicFailed) {
            // AtomicMoveNotSupportedException（跨卷）或占用失败，走校验式回退
            GuardConfig.note("  QUARANTINE_FALLBACK " + jar.getFileName() + " (" + atomicFailed.getClass().getSimpleName() + ")");
        }

        String srcHash = CrashSelfHeal.sha1(jar);
        Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
        String dstHash = CrashSelfHeal.sha1(target);
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
        if (!Files.isDirectory(GuardPaths.QUARANTINE_DIR)) return;
        Map<String, Path> quarantined = new HashMap<String, Path>();
        try {
            List<Path> qJars = new ArrayList<Path>();
            ModJarParser.collectJars(GuardPaths.QUARANTINE_DIR, qJars);
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
            String h1 = CrashSelfHeal.sha1(live);
            String h2 = CrashSelfHeal.sha1(dup);
            if (!h1.isEmpty() && h1.equals(h2)) {
                try {
                    Files.delete(live);
                    it.remove();
                    removed++;
                    System.out.println("[PRTS] 客户端模组预检: 清除隔离残留（mods/ 与隔离区同文件）-> " + live.getFileName());
                    GuardConfig.note("  DUP_RESIDUE_REMOVED " + live.getFileName() + " sha1=" + h1);
                } catch (IOException e) {
                    GuardConfig.note("  DUP_RESIDUE_REMOVE_FAIL " + live.getFileName() + " " + e);
                }
            } else {
                System.out.println("[PRTS] 客户端模组预检: 注意——" + live.getFileName()
                    + " 在 mods/ 与隔离区同名但内容不同（疑似版本升级），保持原样，请人工确认");
                GuardConfig.note("  DUP_NAME_DIFF " + live.getFileName());
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
            Path renamed = jar.resolveSibling(jar.getFileName().toString() + GuardPaths.PENDING_SUFFIX);
            Files.move(jar, renamed, StandardCopyOption.REPLACE_EXISTING);
            GuardConfig.note("  RUNTIME_QUARANTINE_RENAME " + renamed.getFileName());
        }
    }

    private enum Verdict { KEEP, QUARANTINE, REPORT }
}
