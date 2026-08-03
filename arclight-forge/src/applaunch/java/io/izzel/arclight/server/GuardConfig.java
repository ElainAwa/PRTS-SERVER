package io.izzel.arclight.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ClientModGuard 的配置/状态/日志/旧文件迁移（v22 内部重构拆分，行为不变）。 */
public final class GuardConfig {

    private static volatile boolean CUSTOM_FP_LOADED = false;

    private GuardConfig() {}

    // ===================== 日志落盘 =====================

    /** 预检/自愈事件落盘；自愈/崩溃事件额外写 isolation.log（precheck.log 每次启动会被清空重建）。 */
    public static void note(String s) {
        try {
            Files.createDirectories(GuardPaths.CLIENTCHECK_DIR);
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
            String line = "[" + ts + "] " + s + System.lineSeparator();
            Files.write(GuardPaths.PRECHECK_LOG, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (s.startsWith("SELFHEAL") || s.startsWith("SHUTDOWN_HEAL") || s.startsWith("CRASH")
                || s.startsWith("MIXIN_CFG") || s.startsWith("GUARDIAN_ATTRIB")
                || s.startsWith("QUARANTINE_FAIL") || s.startsWith("CRASHREPORT")) {
                Files.write(GuardPaths.CLIENTCHECK_DIR.resolve("isolation.log"), line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) {}
    }

    // ===================== 配置生成（首次启动自动生成带注释 YAML，幂等） =====================

    public static void ensureGuardConfig() {
        boolean seedAuto = false; // 默认关闭隔离(仅报告)；老服种子仅来自 allowlist.json 等 json
        int seedRestarts = 5;     // 自愈重启上限
        Set<String> seedAllow = readAllowlistJsonAllowlist();
        if (!Files.exists(GuardPaths.GUARD_YML)) genGuardYml(seedAuto, seedRestarts, seedAllow);
        genCustomFpSkeleton();
        genCustomFpExample();
        genReadme();
    }

    // 生成带注释的 guard.yml；seed 来自老服既有配置，无则用安全默认
    private static void genGuardYml(boolean auto, int restarts, Set<String> allow) {
        StringBuilder b = new StringBuilder();
        b.append("# ClientModGuard 客户端模组守卫配置（自动生成，可安全编辑；改完重启生效）\n");
        b.append("# 任意字段删除即恢复内置默认。所有字段可选。\n\n");
        b.append("# 客户端模组守卫总开关：true=启用（启动预检隔离 + 运行时崩溃自愈隔离并重启）；false=同步关闭预检隔离与自愈（仅报告，不挪动不重启）\n");
        b.append("autoQuarantine: ").append(auto).append("\n\n");
        b.append("# 自愈重启上限（连续崩溃自动重启次数，0=不重启）\n");
        b.append("maxRestarts: ").append(restarts).append("\n\n");
        b.append("# 白名单：双端/服务端模组写这里，永不隔离（modId 或文件名，小写）\n");
        if (allow.isEmpty()) b.append("allowlist: []\n");
        else { b.append("allowlist:\n"); for (String s : allow) b.append("  - ").append(s).append("\n"); }
        b.append("# 黑名单：强制隔离（即使出现在 allowlist 也优先）\n");
        b.append("denylist: []\n\n");
        b.append("# 无害客户端模组（有客户端代码但无确证危害）是否也隔离；默认 false=无罪推定\n");
        b.append("pruneHarmlessClientMods: false\n\n");
        b.append("# 严格模式：移除一切引用客户端类的模组，追求纯净服务端；默认 false（可能误删双端模组）\n");
        b.append("strictMode: false\n\n");
        b.append("# 权威参考清单（每行一个 modId/文件名或路径），否决启发式隔离（不否决硬证据）\n");
        b.append("trustedModList: []\n\n");
        b.append("# 外部指纹增量文件（只增不覆盖内置），格式 {\"fingerprints\":{\"包/类.class\":\"原因\"}}\n");
        b.append("customFingerprintsFile: ").append(GuardPaths.CUSTOM_FP_FILE.toString()).append("\n");
        try {
            Files.createDirectories(GuardPaths.CLIENTCHECK_DIR);
            Files.write(GuardPaths.GUARD_YML, b.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            note("CONFIG_AUTOGEN guard.yml -> autoQuarantine=" + auto + " maxRestarts=" + restarts + " allow=" + allow.size());
        } catch (IOException e) { note("CONFIG_AUTOGEN_FAIL guard.yml: " + e); }
    }

    // 幂等：骨架/范例/README 仅不存在时生成
    private static void genCustomFpSkeleton() {
        Path p = GuardPaths.CLIENTCHECK_DIR.resolve("custom_fingerprints.json");
        if (Files.exists(p)) return;
        try {
            Files.createDirectories(GuardPaths.CLIENTCHECK_DIR);
            Files.write(p, "{\"fingerprints\":{}}".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        } catch (IOException ignored) {}
    }

    private static void genCustomFpExample() {
        Path p = GuardPaths.CLIENTCHECK_DIR.resolve("custom_fingerprints.example.json");
        if (Files.exists(p)) return;
        String s = "{\n  \"fingerprints\": {\n    \"net/minecraft/client/gui/screens/TitleScreen.class\": \"引用原版客户端界面\"\n  }\n}\n";
        try {
            Files.createDirectories(GuardPaths.CLIENTCHECK_DIR);
            Files.write(p, s.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        } catch (IOException ignored) {}
    }

    private static void genReadme() {
        Path p = GuardPaths.CLIENTCHECK_DIR.resolve("README.md");
        if (Files.exists(p)) return;
        StringBuilder b = new StringBuilder();
        b.append("# _clientcheck 目录说明（自动生成）\n\n");
        b.append("客户端模组守卫(ClientModGuard)的运行与配置文件都在本目录。\n\n");
        b.append("## 可调配置文件\n\n");
        b.append("- `guard.yml` —— **主配置文件**，所有开关与白名单都在这里（带注释，改完重启生效）。\n");
        b.append("- `custom_fingerprints.json` —— 外部类名指纹增量（只增不覆盖内置）。\n");
        b.append("- `custom_fingerprints.example.json` —— 指纹格式参考范例（代码不读，仅参考）。\n\n");
        b.append("## 其它生成文件（勿手改）\n\n");
        b.append("- `precheck.log` 预检日志、`isolation.log` 隔离记录、`state.json` 判定状态、`launch.args` 自愈重启命令行。\n\n");
        b.append("## 常用开关（guard.yml 内）\n\n");
        b.append("- `autoQuarantine: false` 总开关默认关闭：整套客户端模组自检（启动预检+隔离+运行时自愈）完全不启用，不扫描不隔离；设 `true` 才完整启用预检隔离与崩溃自愈。\n");
        b.append("- `allowlist: []` 双端/服务端模组写这里放行。\n");
        b.append("- `maxRestarts: 5` 自愈重启上限。\n");
        try {
            Files.createDirectories(GuardPaths.CLIENTCHECK_DIR);
            Files.write(p, b.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        } catch (IOException ignored) {}
    }

    // 老服种子：旧 allowlist.json/whitelist.json/clientside-guard.json 的白名单并入 guard.yml
    private static Set<String> readAllowlistJsonAllowlist() {
        Set<String> set = new HashSet<String>();
        Path p = GuardPaths.CLIENTCHECK_DIR.resolve("allowlist.json");
        if (!Files.exists(p)) p = GuardPaths.CLIENTCHECK_DIR.resolve("whitelist.json");
        if (!Files.exists(p)) p = Paths.get("clientside-guard.json");
        if (!Files.exists(p)) return set;
        try {
            String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"(allowlist|whitelist)\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
            while (m.find()) {
                Matcher sm = Pattern.compile("\"([^\"]+)\"").matcher(m.group(2));
                while (sm.find()) set.add(sm.group(1).toLowerCase());
            }
        } catch (IOException ignored) {}
        return set;
    }

    // 去掉 YAML 行内/整行注释（值中不含 #，安全）
    static String stripYamlComment(String s) {
        if (s.trim().startsWith("#")) return "";
        int i = s.indexOf(" #");
        if (i >= 0) s = s.substring(0, i);
        return s;
    }

    // ===================== 外部指纹增量 =====================

    // v18: 可选外部指纹增量 _clientcheck/custom_fingerprints.json，格式 {"fingerprints":{"包/类.class":"原因"}}。
    // 只增不覆盖内置条目（放行请用 allowlist，语义清晰且有日志）；文件缺失或解析失败一律静默跳过。
    public static void loadCustomFingerprints() {
        if (CUSTOM_FP_LOADED) return;
        CUSTOM_FP_LOADED = true;
        Path p = GuardPaths.CUSTOM_FP_FILE;
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
                if (key.length() < 8 || key.startsWith("/") || GuardMarkers.KNOWN_BAD_FINGERPRINTS.containsKey(key)) {
                    skipped++;
                    continue;
                }
                String reason = kv.group(2).trim();
                GuardMarkers.KNOWN_BAD_FINGERPRINTS.put(key, (reason.isEmpty() ? "自定义指纹" : reason) + " [custom]");
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

    // ===================== 旧文件迁移（v17b：收敛到 _clientcheck/ + 隔离区去双层） =====================

    /** 启动时一次性迁移：根目录旧 guard 文件 → _clientcheck/，旧隔离区 _quarantine[/clientside] → _disabled_mods/。失败忽略。 */
    public static void migrateLegacyFiles() {
        try { Files.createDirectories(GuardPaths.CLIENTCHECK_DIR); } catch (IOException ignored) {}
        moveRename(Paths.get("_guard_state.json"), GuardPaths.CLIENTCHECK_DIR.resolve("state.json"));
        moveRename(Paths.get("_guard_heal.log"), GuardPaths.CLIENTCHECK_DIR.resolve("isolation.log"));
        moveRename(Paths.get("_guard_precheck.log"), GuardPaths.CLIENTCHECK_DIR.resolve("precheck.log"));
        // v18: 配置文件统一为 _clientcheck/allowlist.json（与字段名 allowlist 对齐）
        moveRename(Paths.get("clientside-guard.json"), GuardPaths.CLIENTCHECK_DIR.resolve("allowlist.json"));
        moveRename(GuardPaths.CLIENTCHECK_DIR.resolve("whitelist.json"), GuardPaths.CLIENTCHECK_DIR.resolve("allowlist.json"));
        // 隔离区改名（v17c）：旧 _quarantine/clientside/* → _quarantine/，再 _quarantine/* → _disabled_mods/
        Path oldQ = Paths.get("_quarantine");
        if (Files.isDirectory(oldQ)) {
            try {
                // 1) 拍平双层 clientside 子目录到 _quarantine/ 根
                Path oldQc = Paths.get("_quarantine", "clientside");
                if (Files.isDirectory(oldQc)) {
                    try {
                        List<Path> oldc = new ArrayList<Path>();
                        ModJarParser.collectJars(oldQc, oldc);
                        for (Path f : oldc) {
                            try { Files.move(f, oldQ.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING); }
                            catch (IOException ignored2) {}
                        }
                        try { Files.deleteIfExists(oldQc); } catch (IOException ignored2) {}
                    } catch (IOException ignored2) {}
                }
                // 2) 整目录 jar 迁到新隔离区
                Files.createDirectories(GuardPaths.QUARANTINE_DIR);
                List<Path> old = new ArrayList<Path>();
                ModJarParser.collectJars(oldQ, old);
                for (Path f : old) {
                    try { Files.move(f, GuardPaths.QUARANTINE_DIR.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING); }
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

    // ===================== 配置 =====================

    public static final class Config {
        final Set<String> whitelist = new HashSet<String>();
        final Set<String> blacklist = new HashSet<String>();
        boolean autoQuarantine = false; // 守卫总开关：true=完整启用整套自检(预检+隔离+自愈)；false=整套关闭，不扫描不隔离不自愈
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
            readGuardYml(c); // canonical（无 guard.yml 则全默认）
            boolean hasGuard = c.sourceFile != null;
            // legacy json（旧名兼容）：仅补充白/黑名单（开关已由 guard.yml 接管）
            Path p = GuardPaths.CLIENTCHECK_DIR.resolve("allowlist.json");
            if (!Files.exists(p)) p = GuardPaths.CLIENTCHECK_DIR.resolve("whitelist.json");
            if (!Files.exists(p)) p = Paths.get("clientside-guard.json");
            if (Files.exists(p)) {
                try {
                    String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    c.whitelist.addAll(parseArray(json, "allowlist"));
                    c.whitelist.addAll(parseArray(json, "whitelist"));
                    c.blacklist.addAll(parseArray(json, "denylist"));
                    c.blacklist.addAll(parseArray(json, "blacklist"));
                    for (String src : parseArrayRaw(json, "trustedModList")) c.loadTrusted(src);
                    if (c.sourceFile == null) c.sourceFile = p.toAbsolutePath().toString();
                } catch (IOException ignored) {}
            }
            // 开关仅由 guard.yml 接管（不再回退 prts.yml）
            return c;
        }

        // v20: 解析 _clientcheck/guard.yml（带注释/缩进容忍），填充 c；失败静默忽略
        static void readGuardYml(Config c) {
            if (!Files.exists(GuardPaths.GUARD_YML)) return;
            try {
                List<String> lines = Files.readAllLines(GuardPaths.GUARD_YML, StandardCharsets.UTF_8);
                String listKey = null;
                for (String raw : lines) {
                    String line = stripYamlComment(raw).replace("\t", " ");
                    if (line.trim().isEmpty()) { listKey = null; continue; }
                    Matcher li = Pattern.compile("^\\s*-\\s+(.+)$").matcher(line);
                    if (li.find() && listKey != null) {
                        addGuardListItem(c, listKey, li.group(1).trim());
                        continue;
                    }
                    Matcher kv = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(.*)$").matcher(line);
                    if (kv.find()) {
                        String key = kv.group(1);
                        String val = kv.group(2).trim();
                        listKey = null;
                        if (val.isEmpty() || val.equals("[]") || val.equals("{}")) { listKey = key; continue; }
                        if (val.startsWith("[")) {
                            Matcher am = Pattern.compile("\"([^\"]+)\"").matcher(val);
                            while (am.find()) addGuardListItem(c, key, am.group(1).trim());
                            continue;
                        }
                        setGuardScalar(c, key, val);
                    } else {
                        listKey = null;
                    }
                }
                c.sourceFile = GuardPaths.GUARD_YML.toAbsolutePath().toString();
            } catch (IOException ignored) {}
        }

        static void addGuardListItem(Config c, String key, String item) {
            if (item.isEmpty()) return;
            if (key.equals("allowlist") || key.equals("whitelist")) c.whitelist.add(item.toLowerCase());
            else if (key.equals("denylist") || key.equals("blacklist")) c.blacklist.add(item.toLowerCase());
            else if (key.equals("trustedModList")) c.loadTrusted(item);
        }

        static void setGuardScalar(Config c, String key, String val) {
            if (key.equals("autoQuarantine")) c.autoQuarantine = Boolean.parseBoolean(val.trim());
            else if (key.equals("maxRestarts")) {
                try { c.maxRestarts = Integer.parseInt(val.trim()); } catch (Exception ignored) {}
            } else if (key.equals("pruneHarmlessClientMods")) c.pruneHarmlessClientMods = Boolean.parseBoolean(val.trim());
            else if (key.equals("strictMode")) c.strictMode = Boolean.parseBoolean(val.trim());
            else if (key.equals("customFingerprintsFile")) {
                Path fp = Paths.get(val.trim());
                if (!fp.isAbsolute()) fp = Paths.get("").toAbsolutePath().resolve(fp);
                GuardPaths.CUSTOM_FP_FILE = fp;
            }
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
                            String id = ModJarParser.detectModMeta(jar).modId;
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
    }

    // ===================== 状态（跨启动记忆） =====================

    /** v10: 跨启动状态记忆。quarantined=我们隔离过的 modId（用于识别"用户加回"）；insistedFailed=连崩计数。 */
    public static final class GuardState {
        final Map<String, Info> quarantined = new LinkedHashMap<String, Info>();
        final Map<String, Info> insistedFailed = new LinkedHashMap<String, Info>();
        final Map<String, ModJarParser.ScanResult> scanCache = new LinkedHashMap<String, ModJarParser.ScanResult>(); // v10c: 扫描结果缓存

        static GuardState load() {
            GuardState s = new GuardState();
            Path p = GuardPaths.CLIENTCHECK_DIR.resolve("state.json");
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
                        ModJarParser.ScanResult sr = new ModJarParser.ScanResult();
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
                for (Map.Entry<String, ModJarParser.ScanResult> e : scanCache.entrySet()) {
                    if (!first) sb.append(",\n");
                    first = false;
                    ModJarParser.ScanResult r = e.getValue();
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
                try { Files.createDirectories(GuardPaths.CLIENTCHECK_DIR); } catch (IOException ignored3) {}
                Files.write(GuardPaths.CLIENTCHECK_DIR.resolve("state.json"), sb.toString().getBytes(StandardCharsets.UTF_8),
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
}
