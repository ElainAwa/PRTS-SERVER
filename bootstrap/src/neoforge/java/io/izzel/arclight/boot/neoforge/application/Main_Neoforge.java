package io.izzel.arclight.boot.neoforge.application;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public class Main_Neoforge {

    public static void main(String[] args) throws Throwable {
        try {
            Map.Entry<String, List<String>> install = forgeInstall();
            var cl = Class.forName(install.getKey());
            var method = cl.getMethod("main", String[].class);
            var target = Stream.concat(install.getValue().stream(), Arrays.stream(args)).toArray(String[]::new);
            method.invoke(null, (Object) target);
        } catch (Exception e) {
            e.printStackTrace();
            // v24b: 启动期崩溃（如 sodium 跑 PreLaunchChecks 缺 org.lwjgl.Version）先交 ClientModGuard 自愈；命中则内部 restart 退出当前 JVM。
            // neoforge 与 applaunch 是独立 source set，编译期不可见，运行时同 classloader，用反射调；不命中则照常 Fail to launch。
            try {
                Class<?> guard = Class.forName("io.izzel.arclight.server.ClientModGuard");
                java.lang.reflect.Method m = guard.getMethod("handleCrash", Throwable.class, String[].class);
                m.invoke(null, e, args);
            } catch (Throwable ignored) {}
            System.err.println("Fail to launch Arclight.");
            System.exit(-1);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<String, List<String>> forgeInstall() throws Throwable {
        var path = Paths.get(".arclight", "gson.jar");
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.copy(Objects.requireNonNull(Main_Neoforge.class.getResourceAsStream("/gson.jar")), path);
        }
        try (var loader = new URLClassLoader(new URL[]{path.toUri().toURL(), Main_Neoforge.class.getProtectionDomain().getCodeSource().getLocation()}, ClassLoader.getPlatformClassLoader())) {
            var cl = loader.loadClass("io.izzel.arclight.installer.NeoforgeInstaller");
            var handle = MethodHandles.lookup().findStatic(cl, "applicationInstall", MethodType.methodType(Map.Entry.class));
            return (Map.Entry<String, List<String>>) handle.invoke();
        }
    }
}
