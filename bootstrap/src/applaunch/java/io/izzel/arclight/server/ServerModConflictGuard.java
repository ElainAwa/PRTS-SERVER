package io.izzel.arclight.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端冲突模组硬检测：核心已内置 ServerCore/VMP/HariPlayer 功能，
 * 启动早期发现 mods/ 里装有这些模组即自动移入 _disabled_mods/，避免双重 mixin 注入冲突。
 * 恒开（不受 ClientModGuard.autoQuarantine 影响）；异常仅记录不阻塞启动。
 */
public final class ServerModConflictGuard {

    private static final Set<String> CONFLICT_MODIDS = new HashSet<String>(Arrays.asList("servercore", "vmp", "hariplayer"));
    private static final String[] NAME_HINTS = {"servercore", "vmp", "hariplayer", "very-many-players"};
    private static final Path QUARANTINE_DIR = Paths.get("_disabled_mods");
    private static final Pattern FABRIC_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private ServerModConflictGuard() {
    }

    public static void run() {
        try {
            String prop = System.getProperty("fml.modsDir");
            Path modsDir = prop != null ? Paths.get(prop) : Paths.get("mods");
            if (!Files.isDirectory(modsDir)) return;
            Files.createDirectories(QUARANTINE_DIR);
            int moved = 0;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path jar : ds) {
                    String modId = detectModId(jar);
                    String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (modId != null && CONFLICT_MODIDS.contains(modId)) {
                        quarantine(jar, "modid=" + modId);
                        moved++;
                    } else if (modId == null && matchesHint(name)) {
                        quarantine(jar, "filename=" + name);
                        moved++;
                    }
                }
            }
            if (moved > 0) {
                System.out.println("[PRTS] 冲突模组硬检测: 已自动隔离 " + moved + " 个与核心内置功能冲突的模组至 " + QUARANTINE_DIR.toAbsolutePath());
            }
        } catch (Throwable t) {
            System.err.println("[PRTS] 冲突模组硬检测异常（已跳过，不影响启动）: " + t);
        }
    }

    private static void quarantine(Path jar, String reason) {
        try {
            Path target = QUARANTINE_DIR.resolve(jar.getFileName().toString());
            if (Files.exists(target)) Files.delete(target);
            Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[PRTS] 已隔离冲突模组 " + jar.getFileName() + "（" + reason + "）→ " + QUARANTINE_DIR);
        } catch (java.io.IOException ignored) {
            System.err.println("[PRTS] 冲突模组隔离失败（文件占用，留待下轮）: " + jar.getFileName());
        }
    }

    // 优先 mods.toml/neoforge.mods.toml modid；空则 fabric.mod.json id
    private static String detectModId(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (String name : new String[]{"META-INF/mods.toml", "META-INF/neoforge.mods.toml"}) {
                if (jf.getJarEntry(name) == null) continue;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(jf.getInputStream(jf.getJarEntry(name)), StandardCharsets.UTF_8))) {
                    String line;
                    boolean inMods = false;
                    while ((line = br.readLine()) != null) {
                        String t = line.trim();
                        if (t.startsWith("[[mods]]")) { inMods = true; continue; }
                        if (t.startsWith("[") && !t.startsWith("[[mods]]")) inMods = false;
                        if (inMods && t.startsWith("modId=")) {
                            String v = t.substring(6).trim().replace("\"", "");
                            if (!v.isEmpty()) return v.toLowerCase(Locale.ROOT);
                        }
                    }
                }
            }
            if (jf.getJarEntry("fabric.mod.json") != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(jf.getInputStream(jf.getJarEntry("fabric.mod.json")), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[8192];
                    int n;
                    while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                    Matcher m = FABRIC_ID.matcher(sb);
                    if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return null;
    }

    private static boolean matchesHint(String name) {
        for (String h : NAME_HINTS) {
            if (name.contains(h)) return true;
        }
        return false;
    }
}
