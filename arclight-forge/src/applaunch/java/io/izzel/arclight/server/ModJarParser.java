package io.izzel.arclight.server;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ClientModGuard 的 jar/元数据/mixin 字节解析（v22 内部重构拆分，行为不变）。applaunch 阶段无第三方库，全部手写轻量解析。 */
public final class ModJarParser {

    /** 缓存键=算法版本+文件名+大小+修改时间；改扫描判据须 bump SCAN_ALGO_VERSION，否则旧缓存信号失准。 */
    private static final String SCAN_ALGO_VERSION = "v14";

    private ModJarParser() {}

    /** 单个模组 jar 的元数据（mods.toml / neoforge.mods.toml / fabric.mod.json）。 */
    public static final class ModMeta {
        public String modId;
        public String displayName; // v14: mods.toml displayName / fabric.mod.json name，用于反查 Forge 报错点名的模组
        public String environment;
        public boolean clientSideOnly; // 根级 clientSideOnly=true（Forge 专用服会跳过该模组）
        public final Set<String> dependencies = new HashSet<String>();
    }

    /** 单个 jar 的字节扫描结果（含 mixin 静态检测信号）。 */
    public static final class ScanResult {
        public boolean hasClient;
        public boolean hasServer;
        public boolean hasContent;
        public boolean hasCommonMixin;
        public boolean hasDistGuard;   // 双端守卫(Dist/EnvType 自检)：安全的客户端模组
        public boolean hasBroadGuard;  // 宽泛守卫(@OnlyIn/EnvType/Environment 常量)：至少有过 dist 意识
        public boolean hasKjsPlugin;   // KubeJS 插件(kubejs.plugins.txt)：双端 KubeJS 附属
        // v15: 中毒 mixin —— 非空表示「注入服务端必加载的原版类 + 体内调用客户端类」，专用服上必崩。
        // v16: 客户端目标 mixin —— @Mixin 目标本身是 net/minecraft/client/** 下的客户端类，专用服上必 InvalidMixinException 崩。
        public String poisonMixin;
        public String clientTargetMixin;
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

    /** 解析 jar 元数据（Forge/NeoForge mods.toml + fabric.mod.json 兜底），读不到则用文件名当 modId。 */
    public static ModMeta detectModMeta(Path jar) {
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
        } catch (IOException e) { GuardConfig.note("DETECT_META_FAIL " + jar.getFileName() + " " + e); }
        if (meta.modId == null) {
            String fn = jar.getFileName().toString().toLowerCase();
            if (fn.endsWith(".jar")) fn = fn.substring(0, fn.length() - 4);
            meta.modId = fn;
        }
        return meta;
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

    /** 全量字节扫描单 jar：双端信号 + 内容/KubeJS 信号 + 中毒 mixin / 客户端目标 mixin 静态检测。 */
    public static ScanResult scanJarFull(Path jar) {
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
                } catch (IOException ex) { GuardConfig.note("SCAN_ENTRY_FAIL " + e.getName() + " " + ex); }
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
                    for (String m : GuardMarkers.CLIENT_MARKERS) if (contains(b, m)) { cli = true; break; }
                    if (cli) anyPoison = true; else anyClean = true;
                } catch (IOException ignored) {}
            }
            if (anyClean && !anyPoison) r.hasCommonMixin = true;
            r.poisonMixin = detectPoisonMixin(jf, jar);
            // 注意：detectClientTargetMixin 与原版一致保持未接线（v16 实测误判，仅观察不隔离）。
        } catch (IOException ignored) {}
        return r;
    }

    private static void checkBytes(byte[] b, ScanResult r, boolean skipClientSignal) {
        if (!r.hasClient && !skipClientSignal) {
            for (String m : GuardMarkers.CLIENT_MARKERS) if (contains(b, m)) { r.hasClient = true; break; }
        }
        if (!r.hasServer) {
            for (String m : GuardMarkers.SERVER_MARKERS) if (contains(b, m)) { r.hasServer = true; break; }
        }
        if (!r.hasContent) {
            for (String m : GuardMarkers.CONTENT_MARKERS) if (contains(b, m)) { r.hasContent = true; break; }
        }
        if (!r.hasDistGuard) {
            for (String m : GuardMarkers.DIST_GUARD_MARKERS) if (contains(b, m)) { r.hasDistGuard = true; break; }
        }
        if (!r.hasBroadGuard) {
            for (String m : GuardMarkers.BROAD_GUARD_MARKERS) if (contains(b, m)) { r.hasBroadGuard = true; break; }
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

    // ==================== v15：中毒 mixin 静态检测（L1 硬证据，启动前） ====================
    // 注入原版必加载服务端类且体内调用客户端类的 mixin 专用服加载即 MixinTransformerError 且不写崩溃报告，须启动前拦截；五条判据同时满足才隔离（环境非CLIENT、无CLIENT注解/@Pseudo、常量池真调用客户端类、@Mixin目标为原版非客户端类）。
    private static String detectPoisonMixin(JarFile jf, Path jar) {
        List<String> cfgs = collectMixinConfigs(jf);
        int budget = GuardMarkers.MIXIN_SCAN_BUDGET;
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
                    GuardConfig.note("  MIXIN_WATCH " + jar.getFileName() + " " + desc
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
        int budget = GuardMarkers.MIXIN_SCAN_BUDGET;
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

    /** 收集 jar 根目录下的所有 mixin 配置文件名（mixin 配置一律在 jar 根）。 */
    static List<String> collectMixinConfigs(JarFile jf) {
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

    /** 类名指纹匹配（加速提示）。 */
    static String matchFingerprint(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (Map.Entry<String, String> e : GuardMarkers.KNOWN_BAD_FINGERPRINTS.entrySet()) {
                if (jf.getJarEntry(e.getKey()) != null) return e.getValue();
            }
        } catch (IOException ignored) {}
        return null;
    }

    /** 收集目录下所有 .jar（含子目录）。 */
    static void collectJars(Path dir, final List<Path> out) throws IOException {
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

    /** 扫描结果缓存键：文件名+大小+修改时间；取不到元信息则退化（每轮重扫）。 */
    static String cacheKey(Path jar) {
        try {
            return SCAN_ALGO_VERSION + "|" + jar.getFileName() + ":" + Files.size(jar)
                + ":" + Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException e) {
            return SCAN_ALGO_VERSION + "|" + jar.getFileName();
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** 无服务端信号且无分服务端守卫的裸客户端模组（疑似纯客户端），用于守护者归因判定。 */
    public static boolean isUnguardedClient(ScanResult r) {
        return r != null && r.hasClient && !r.hasServer && !r.hasDistGuard && !r.hasBroadGuard;
    }
}
