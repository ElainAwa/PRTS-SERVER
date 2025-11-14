package io.izzel.arclight.i18n;

import com.google.common.reflect.TypeToken;
import io.izzel.arclight.i18n.conf.ConfigSpec;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
            instance = createInitialConfig(path);
        } else {
            instance = loadExistingConfig(path);
        }

        LoggingConfigurator.apply(instance.spec);
    }

    private static ArclightConfig createInitialConfig(Path path) throws Exception {
        String currentLocale = ArclightLocale.getInstance().current();
        try (InputStream is = ArclightConfig.class.getResourceAsStream("/META-INF/luminara.yml")) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String processed = I18nCommentInjector.injectComments(content, currentLocale);
            Files.write(path, processed.getBytes(StandardCharsets.UTF_8));
        }

        YAMLConfigurationLoader loader = YAMLConfigurationLoader.builder()
                .setPath(path)
                .setIndent(2)
                .build();
        ConfigurationNode cur = loader.load();
        return new ArclightConfig(cur);
    }

    private static ArclightConfig loadExistingConfig(Path path) throws Exception {
        YAMLConfigurationLoader loader = YAMLConfigurationLoader.builder()
                .setPath(path)
                .setIndent(2)
                .build();
        ConfigurationNode cur = loader.load();

        cur.getNode("locale", "current").setValue(ArclightLocale.getInstance().current());
        return new ArclightConfig(cur);
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
