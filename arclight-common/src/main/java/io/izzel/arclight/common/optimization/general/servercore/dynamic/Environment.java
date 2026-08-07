package io.izzel.arclight.common.optimization.general.servercore.dynamic;

/**
 * 运行环境常量（移植自 ServerCore utils.Environment）。
 * PRTS 服务端核心恒为专用服务端，无客户端/Spark 依赖。
 */
public final class Environment {
    public static final boolean CLIENT = false;
    public static final boolean MOD_SPARK = false;

    private Environment() {
    }
}
