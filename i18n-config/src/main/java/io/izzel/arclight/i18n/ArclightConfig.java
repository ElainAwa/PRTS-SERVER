package io.izzel.arclight.i18n;

import com.google.common.reflect.TypeToken;
import io.izzel.arclight.i18n.conf.ConfigSpec;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public class ArclightConfig {

    private static ArclightConfig instance;

    static {
        try {
            load();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final ConfigurationNode node;
    private final ConfigSpec spec;

    public ArclightConfig(ConfigurationNode node) throws ObjectMappingException {
        this.node = node;
        this.spec = this.node.getValue(TypeToken.of(ConfigSpec.class));
    }

    public static ConfigSpec spec() {
        return instance.spec;
    }

    private static void load() throws Exception {
        Path path = Paths.get("luminara.yml");
        boolean fileExisted = Files.exists(path);

        if (!fileExisted) {
            try (InputStream is = ArclightConfig.class.getResourceAsStream("/META-INF/luminara.yml")) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                Files.write(path, content.getBytes(StandardCharsets.UTF_8));
                injectI18nComments(path);
                updateLocaleInExistingFile(path);
            }

            YAMLConfigurationLoader loader = YAMLConfigurationLoader.builder()
                    .setPath(path)
                    .setIndent(2)
                    .build();
            ConfigurationNode cur = loader.load();
            instance = new ArclightConfig(cur);

        } else {
            YAMLConfigurationLoader loader = YAMLConfigurationLoader.builder()
                    .setPath(path)
                    .setIndent(2)
                    .build();
            ConfigurationNode cur = loader.load();

            cur.getNode("locale", "current").setValue(ArclightLocale.getInstance().current());
            instance = new ArclightConfig(cur);

        }

        applyLoggingConfiguration();
    }

    private static boolean fileContainsCurrentLocale(String content, String currentLocale) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("current:\\s*" + java.util.regex.Pattern.quote(currentLocale));
        return pattern.matcher(content).find();
    }

    private static void updateLocaleInExistingFile(Path path) throws Exception {
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String currentLocale = ArclightLocale.getInstance().current();

        if (content.contains("current:")) {
            content = content.replaceAll("(?m)^([\\t ]*current:)\\s*[a-zA-Z_0-9]+$", "$1 " + currentLocale);
        } else {
            content = content.replaceFirst(
                    "(locale:\\s*\\n\\s*fallback:\\s*zh_cn)",
                    "$1\n  current: " + currentLocale
            );
        }

        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void injectI18nComments(Path configPath) throws Exception {
        String currentLocale = ArclightLocale.getInstance().current();
        InputStream i18nStream = ArclightConfig.class.getResourceAsStream("/META-INF/i18n/" + currentLocale + ".yml");

        if (i18nStream == null) {
            String fallbackLocale = ArclightLocale.getInstance().fallback();
            i18nStream = ArclightConfig.class.getResourceAsStream("/META-INF/i18n/" + fallbackLocale + ".yml");
        }

        if (i18nStream == null) {
            return;
        }

        final InputStream finalI18nStream = i18nStream;
        YAMLConfigurationLoader i18nLoader = YAMLConfigurationLoader.builder()
                .setSource(() -> new BufferedReader(new InputStreamReader(finalI18nStream, StandardCharsets.UTF_8)))
                .build();
        ConfigurationNode i18nNode = i18nLoader.load();
        ConfigurationNode commentsNode = i18nNode.getNode("comments");

        if (commentsNode.isVirtual()) {
            return;
        }

        String configContent = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();

        String[] lines = configContent.split("\n");
        java.util.List<String> currentPath = new java.util.ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            int indentLevel = getIndentLength(line);

            if (!line.trim().startsWith("#") && !line.trim().isEmpty()) {
                String key = extractKeyFromLine(line);
                if (key != null) {
                    int currentLevel = Math.max(0, indentLevel / 2);

                    while (currentPath.size() > currentLevel) {
                        currentPath.remove(currentPath.size() - 1);
                    }

                    currentPath.add(key);

                    String fullPath = String.join(".", currentPath);
                    String commentPath = fullPath + ".comment";

                    ConfigurationNode commentNode = commentsNode.getNode((Object[]) commentPath.split("\\."));
                    if (!commentNode.isVirtual() && commentNode.getValue() != null) {
                        String commentText = getCommentText(commentNode);
                        if (!commentText.isEmpty()) {
                            String indent = getIndent(line);
                            String[] commentLines = commentText.split("\n");
                            for (String comment : commentLines) {
                                if (!comment.trim().isEmpty()) {
                                    result.append(indent).append("# ").append(comment).append("\n");
                                }
                            }
                        }
                    }
                }
            }

            result.append(line).append("\n");
        }

        Files.write(configPath, result.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String extractKeyFromLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }

        int colonIndex = trimmed.indexOf(':');
        if (colonIndex > 0) {
            return trimmed.substring(0, colonIndex).trim();
        }
        return null;
    }

    private static String getCommentText(ConfigurationNode commentNode) {
        Object value = commentNode.getValue();
        if (value instanceof String) {
            return (String) value;
        } else if (value instanceof Iterable) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (Iterable<?>) value) {
                if (item instanceof String) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append((String) item);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static String getIndent(String line) {
        int indentLength = 0;
        while (indentLength < line.length() && Character.isWhitespace(line.charAt(indentLength))) {
            indentLength++;
        }
        return line.substring(0, indentLength);
    }

    private static int getIndentLength(String line) {
        int indentLength = 0;
        while (indentLength < line.length() && Character.isWhitespace(line.charAt(indentLength))) {
            indentLength++;
        }
        return indentLength;
    }

    private static void applyLoggingConfiguration() {
        try {
            boolean useSimpleFormat;
            try {
                useSimpleFormat = instance.spec.getLogging() != null
                        && instance.spec.getLogging().isUseSimpleFormat();
            } catch (Exception e) {
                useSimpleFormat = false;
            }

            String configFile = useSimpleFormat ? "arclight-log4j2.xml" : "arclight-log4j2-detailed.xml";
            reconfigureLogging(configFile);

            System.out.println("[Luminara] Applied logging configuration: " +
                    (useSimpleFormat ? "Simple format" : "Detailed format"));

        } catch (Exception e) {
            System.err.println("Failed to apply logging configuration: " + e.getMessage());
        }
    }

    private static void reconfigureLogging(String configFile) {
        try {
            ClassLoader classLoader = ArclightConfig.class.getClassLoader();
            URI configUri = Objects.requireNonNull(classLoader.getResource(configFile)).toURI();

            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.setConfigLocation(configUri);
            context.reconfigure();

        } catch (Exception e) {
            System.err.println("Failed to reconfigure logging: " + e.getMessage());
            System.setProperty("log4j.configurationFile", configFile);
        }
    }

    public ConfigSpec getSpec() {
        return spec;
    }

    public ConfigurationNode getNode() {
        return node;
    }

    public ConfigurationNode get(String path) {
        return this.node.getNode((Object) path.split("\\."));
    }
}
