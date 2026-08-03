package io.izzel.arclight.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ClientModGuard 的崩溃解析/守护者归因/自愈重启（v22 内部重构拆分，行为不变）。 */
public final class CrashSelfHeal {

    /** v16：从异常链消息里提取「Mixin 配置名」（如 betterlockon.mixins.json），用于直接归属崩溃属主。 */
    private static final Pattern MIXIN_CFG_PATTERN =
        Pattern.compile("([\\w.+-]+\\.mixins?\\.json)(?::|\\b)");

    /** v17：移除类祈使语义关键词。ASCII 走归一化文本，中文只能走原文（归一化会清掉非 a-z0-9）。 */
    private static final String[] REMOVE_HINT_ASCII = {
        "removeit", "pleaseremove", "mustberemoved", "shouldberemoved", "removethe",
        "removethis", "deleteit", "pleasedelete", "uninstall",
        "notcompatible", "incompatiblewith", "isincompatible", "donotuse", "shouldnotbeinstalled"
    };
    private static final String[] REMOVE_HINT_RAW = {
        "请移除", "请删除", "移除它", "删除它", "需要移除", "不兼容", "请卸载"
    };

    private CrashSelfHeal() {}

    /** FML/NeoForge 在模组加载失败时吞掉真实异常，仅抛通用消息。 */
    public static boolean isModLoadingFailed(Throwable t) {
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
    public static List<String[]> parseCrashReportClientFailures(long sinceMillis) {
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
                    if (GuardMarkers.containsClientPrefix(l) && (l.contains("invalid dist")
                        || l.contains("NoClassDefFoundError") || l.contains("ClassNotFoundException"))) {
                        missing = GuardMarkers.extractClientClass(l);
                        break;
                    }
                }
                if (missing != null) out.add(new String[]{modId, missing});
            }
            if (!out.isEmpty()) GuardConfig.note("CRASHREPORT " + newest.getFileName() + " clientFailures=" + out.size());
        } catch (Throwable e) { GuardConfig.note("CRASHREPORT_PARSE_FAIL " + e); }
        return out;
    }

    /** v15：解析崩溃报告中 Forge 点名的加载失败模组（段内 Failure message 或 mixin 配置属主），只取证不隔离。 */
    public static List<String[]> parseCrashReportModFailures(long sinceMillis) {
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
            if (!out.isEmpty()) GuardConfig.note("CRASHREPORT_MODFAIL " + newest.getFileName() + " candidates=" + out.size());
        } catch (Throwable e) { GuardConfig.note("CRASHREPORT_MODFAIL_PARSE_FAIL " + e); }
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

    /** v14: 从 Forge LoadingFailedException 消息中提取被点名的模组（展示名或 modId）。 */
    public static List<String> parseLoadingFailureNames(Throwable t) {
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
    public static Path findJarByModIdOrName(List<Path> jars, String name) {
        String key = normName(name);
        if (key.isEmpty()) return null;
        for (Path jar : jars) {
            try {
                ModJarParser.ModMeta m = ModJarParser.detectModMeta(jar);
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

    public static Path findJarByModId(List<Path> jars, String modId) {
        for (Path jar : jars) {
            try {
                if (modId.equals(ModJarParser.detectModMeta(jar).modId)) return jar;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** v16：定位到的模组若为守护者（自身双端安全，仅点名他人），从异常消息反查并隔离被点名的真·客户端模组，保留守护者。 */
    public static Path resolveRealCulprit(Throwable t, Path located, List<Path> jars) {
        // 1) 定位到的模组本身就是纯客户端模组 → 它即真凶，无需改判
        ModJarParser.ScanResult lr = null;
        try { lr = ModJarParser.scanJarFull(located); } catch (Throwable ignored) {}
        if (ModJarParser.isUnguardedClient(lr)) {
            return located;
        }
        // 2) 定位到的是「守护者」：从异常消息里找被点名的真·客户端模组（如 tcrcore 点名 aaa_particles）
        String msg = normalizeForMatch(summarize(t));
        for (Path jar : jars) {
            if (jar.equals(located)) continue;
            if (!referencedInMessage(msg, jar.getFileName().toString())) continue;
            ModJarParser.ScanResult r2 = null;
            try { r2 = ModJarParser.scanJarFull(jar); } catch (Throwable ignored) {}
            if (ModJarParser.isUnguardedClient(r2)) {
                GuardConfig.note("  v16: 异常消息点名 " + jar.getFileName() + "，其为纯客户端模组，隔离之（守护者 "
                    + located.getFileName() + " 保留）");
                return jar;
            }
        }
        return null;
    }

    /** v17 守护者归因：崩溃文本含「移除/不兼容」祈使句且能唯一点名他人时，隔离被点名者、保留报警的守护者（不要求被点名者是纯客户端）。 */
    /** v17b 家族连坐：directHit 取核心名 familyCore，凡双向前缀匹配的同家族姊妹包一并隔离；多家族歧义则退回保留。 */
    public static List<Path> resolveGuardianCulprit(String attributionText, Path guardian, List<Path> jars, GuardConfig.Config cfg) {
        if (attributionText == null || attributionText.isEmpty()) return new ArrayList<Path>();
        String norm = normalizeForMatch(attributionText);
        if (!hasRemovalDirective(attributionText.toLowerCase(), norm)) {
            GuardConfig.note("GUARDIAN_ATTRIB_MISS no-directive " + guardian.getFileName());
            return new ArrayList<Path>();
        }
        // 1) 被消息直接点名的 jar（消息含其归一化核心名）
        List<Path> direct = new ArrayList<Path>();
        for (Path jar : jars) {
            if (jar.equals(guardian)) continue;
            if (referencedInMessage(norm, jar.getFileName().toString())) direct.add(jar);
        }
        if (direct.isEmpty()) {
            GuardConfig.note("GUARDIAN_ATTRIB_MISS no-target " + guardian.getFileName());
            return new ArrayList<Path>();
        }
        // 2) 多个 directHit 分属不同家族 → 歧义，保 guardian
        String familyCore = jarCore(direct.get(0).getFileName().toString());
        for (Path d : direct) {
            if (!jarCore(d.getFileName().toString()).equals(familyCore)) {
                GuardConfig.note("GUARDIAN_ATTRIB_MISS ambiguous " + direct.get(0).getFileName() + " / " + d.getFileName());
                return new ArrayList<Path>();
            }
        }
        // 3) 家族连坐：同前缀（双向）姊妹包一并隔离
        List<Path> out = familyMembers(familyCore, jars, guardian, cfg);
        if (out.isEmpty()) GuardConfig.note("GUARDIAN_ATTRIB_MISS no-family " + guardian.getFileName());
        return out;
    }

    /** v17b：以 familyCore 为前缀，收集所有同家族姊妹包（排除 guardian、白名单/L0 覆盖）。 */
    private static List<Path> familyMembers(String familyCore, List<Path> jars, Path guardian, GuardConfig.Config cfg) {
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
    private static boolean isWhitelisted(Path jar, GuardConfig.Config cfg) {
        if (cfg == null) return false;
        String name = jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (cfg.whitelist.contains(name)) return true;
        String id;
        try { id = ModJarParser.detectModMeta(jar).modId.toLowerCase(java.util.Locale.ROOT); }
        catch (Throwable e) { return false; }
        return cfg.whitelist.contains(id);
    }

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
    public static boolean isClientClassMissing(Throwable t) {
        Throwable c = t;
        while (c != null) {
            String msg = c.getMessage();
            if (msg != null && GuardMarkers.containsClientPrefix(msg)) return true;
            c = c.getCause();
        }
        return false;
    }

    /** 从异常链里提取缺失的客户端类名（用于日志/状态记录）。 */
    public static String missingClassName(Throwable t) {
        Throwable c = t;
        while (c != null) {
            String msg = c.getMessage();
            if (msg != null) {
                for (String p : GuardMarkers.CLIENT_CLASS_PREFIX_DOT) {
                    int i = msg.indexOf(p);
                    if (i >= 0) return msg.substring(i);
                }
                for (String p : GuardMarkers.CLIENT_CLASS_PREFIX_SLASH) {
                    int i = msg.indexOf(p);
                    if (i >= 0) return msg.substring(i);
                }
            }
            c = c.getCause();
        }
        return "unknown-client-class";
    }

    public static String summarize(Throwable t) {
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
    public static Path locateOffendingMod(Throwable t) {
        Path dir = GuardPaths.MODS_DIR != null && Files.isDirectory(GuardPaths.MODS_DIR)
            ? GuardPaths.MODS_DIR : Paths.get(System.getProperty("fml.modsDir", "mods"));
        List<Path> jars = new ArrayList<Path>();
        try { ModJarParser.collectJars(dir, jars); } catch (IOException ignored) {}
        Throwable c = t;
        int frames = 0;
        while (c != null && frames < 64) {
            for (StackTraceElement ste : c.getStackTrace()) {
                frames++;
                String cn = ste.getClassName();
                if (GuardMarkers.isCoreClass(cn)) continue;
                String path = cn.replace('.', '/') + ".class";
                Path jar = findJarContainingClass(jars, path);
                if (jar != null) return jar;
            }
            c = c.getCause();
        }
        return null;
    }

    private static Path findJarContainingClass(List<Path> jars, String classPath) {
        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                if (jf.getJarEntry(classPath) != null) return jar;
            } catch (IOException ignored) {}
        }
        return null;
    }

    public static List<String> parseMixinConfigNames(Throwable t) {
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
    public static List<String> parseMixinConfigNamesFromLog() {
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
                    GuardConfig.note("MIXIN_CFG_SKIP benign (server reached Done after "
                        + out.get(0) + " failure)");
                    return new ArrayList<String>();
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static Path findJarContainingEntry(List<Path> jars, String entry) {
        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                if (jf.getJarEntry(entry) != null) return jar;
            } catch (IOException ignored) {}
        }
        return null;
    }

    // ===================== 自愈重启 =====================

    public static void restart(String[] args) {
        int retry = currentRetry() + 1;
        if (retry > GuardPaths.MAX_RESTART) {
            System.err.println("[PRTS] 自愈重启次数已达上限(" + GuardPaths.MAX_RESTART + ")，停止自动重启。请检查并清理 mods/ 中的客户端模组。");
            System.exit(1);
            return;
        }
        List<String> cmd = readPersistedLaunchArgs();
        if (cmd == null) cmd = buildCommand(args);
        else GuardConfig.note("RESTART_ARGS 复用 " + GuardPaths.LAUNCH_ARGS_FILE);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        pb.environment().put(GuardPaths.RETRY_ENV, String.valueOf(retry));
        try {
            pb.start();
            System.exit(0);
        } catch (IOException e) {
            System.err.println("[PRTS] 自愈重启失败（无法启动新 JVM）: " + e);
            System.exit(1);
        }
    }

    public static int currentRetry() {
        String v = System.getenv(GuardPaths.RETRY_ENV);
        if (v == null) return 0;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; }
    }

    /** 重建启动命令：JVM 参数取自 RuntimeMXBean，主入口另行还原（getInputArguments 不含 -jar/主类，旧版 hasJar 分支恒为 false）。 */
    public static List<String> buildCommand(String[] args) {
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
    public static void persistLaunchArgs(String[] args) {
        try {
            Files.createDirectories(GuardPaths.CLIENTCHECK_DIR);
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
            Files.write(GuardPaths.LAUNCH_ARGS_FILE, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    /** 读回持久化命令行；缺失/token 过少/首项不存在一律返回 null，由调用方回退自动重建。 */
    private static List<String> readPersistedLaunchArgs() {
        try {
            if (!Files.exists(GuardPaths.LAUNCH_ARGS_FILE)) return null;
            List<String> cmd = new ArrayList<String>();
            for (String line : Files.readAllLines(GuardPaths.LAUNCH_ARGS_FILE, StandardCharsets.UTF_8)) {
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

    public static String sha1(Path p) {
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
