package io.izzel.arclight.server;

import java.nio.file.Path;
import java.nio.file.Paths;

/** ClientModGuard 路径与自愈重启参数常量（v22 内部重构拆分，行为不变）。 */
public final class GuardPaths {

    public static final Path QUARANTINE_DIR = Paths.get("_disabled_mods");
    // v17b：预检/自愈/状态/白名单等生成文件统一收敛到此目录（改动 B）
    public static final Path CLIENTCHECK_DIR = Paths.get("_clientcheck");
    // v20: 统一配置落点（带注释 YAML，首次启动自动生成），所有开关/白名单集中于此
    public static final Path GUARD_YML = CLIENTCHECK_DIR.resolve("guard.yml");
    // v20: 外部指纹增量文件路径，可被 guard.yml 的 customFingerprintsFile 覆盖；默认骨架路径
    public static Path CUSTOM_FP_FILE = CLIENTCHECK_DIR.resolve("custom_fingerprints.json");
    // 运行时隔离失败（Windows 文件占用）时的同目录改名后缀；下次启动预扫描会把它真正移走。
    public static final String PENDING_SUFFIX = ".prts-quarantined";
    public static final Path PRECHECK_LOG = CLIENTCHECK_DIR.resolve("precheck.log");
    // v18: boot 期落盘的自愈重启命令行，服主可手工修正；自愈时优先复用，非法则回退自动重建。
    public static final Path LAUNCH_ARGS_FILE = CLIENTCHECK_DIR.resolve("launch.args");
    public static Path MODS_DIR;

    // 自愈重启上限（单次会话最多自动重启次数，防级联/失控）；run() 里被配置覆盖
    public static int MAX_RESTART = 5;
    public static final String RETRY_ENV = "PRTS_GUARD_RETRY";

    private GuardPaths() {}
}
