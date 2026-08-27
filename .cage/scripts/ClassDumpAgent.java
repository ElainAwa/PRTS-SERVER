import java.io.File;
import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * PRTS 字节码抓取 agent（自写，最小依赖）。
 *
 * 用法（JVM 启动参数）：
 *   -javaagent:ClassDumpAgent.jar=match=net.minecraft.world.entity.ai.behavior.AcquirePoi;out=classdump
 *
 * 参数：
 *   match=<类名子串>      要导出的类（点分或斜杠均可，支持子串匹配）
 *   out=<输出目录>        默认 classdump
 *   list=1                启动时列出所有已加载且匹配 match 的类名（不导出）
 *   retransform=<类名>    对已加载的匹配类触发 retransformClasses，
 *                         重新走一遍 transformer 链并把字节写入 out
 *
 * 打包：
 *   javac ClassDumpAgent.java
 *   jar cfm ClassDumpAgent.jar MANIFEST.MF ClassDumpAgent.class ClassDumpAgent$1.class
 *   MANIFEST.MF 内容：
 *     Manifest-Version: 1.0
 *     Premain-Class: ClassDumpAgent
 *     Can-Retransform-Classes: true
 */
public class ClassDumpAgent {

    private static String outDir = "classdump";
    private static String match = "";
    private static boolean listOnly = false;

    public static void premain(String args, Instrumentation inst) {
        parseArgs(args);

        if (listOnly) {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String n = c.getName();
                if (n != null && n.contains(match)) {
                    System.out.println("[ClassDumpAgent] loaded: " + n);
                }
            }
            return;
        }

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (className != null && className.contains(match)) {
                    String name = className.replace('/', '.');
                    dump(name, classfileBuffer);
                }
                // 返回 null = 不修改字节码，只观察
                return null;
            }
        }, true);

        String retransform = System.getProperty("agent.retransform", "");
        if (!retransform.isEmpty()) {
            try {
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    if (c.getName().contains(retransform)) {
                        inst.retransformClasses(c);
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static void parseArgs(String args) {
        if (args == null) return;
        for (String part : args.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0].trim()) {
                case "match" -> match = kv[1].trim().replace('/', '.');
                case "out" -> outDir = kv[1].trim();
                case "list" -> listOnly = "1".equals(kv[1].trim()) || "true".equalsIgnoreCase(kv[1].trim());
                default -> { }
            }
        }
    }

    private static void dump(String className, byte[] bytes) {
        try {
            File f = new File(outDir, className.replace('.', '/') + ".class");
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(bytes);
            }
            System.out.println("[ClassDumpAgent] dumped " + className + " -> " + f.getPath() + " (" + bytes.length + " bytes)");
        } catch (Exception e) {
            System.err.println("[ClassDumpAgent] dump failed for " + className + ": " + e);
        }
    }
}
