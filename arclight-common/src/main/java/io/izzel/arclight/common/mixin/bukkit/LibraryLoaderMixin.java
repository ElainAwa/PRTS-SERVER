package io.izzel.arclight.common.mixin.bukkit;

import io.izzel.arclight.common.mod.util.remapper.generated.RemappingURLClassLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.URL;
import java.net.URLClassLoader;

@Mixin(targets = "org.bukkit.plugin.java.LibraryLoader", remap = false)
public class LibraryLoaderMixin {

    private static final String LUMINARA_MAVEN_REPO_PROPERTY = "luminara.maven.repo";
    private static final String BUKKIT_REPO_PROPERTY = "org.bukkit.plugin.java.LibraryLoader.centralURL";

    @Redirect(method = "createLoader", at = @At(value = "NEW", target = "java/net/URLClassLoader"))
    private URLClassLoader arclight$useRemapped(URL[] urls, ClassLoader loader) {
        return new RemappingURLClassLoader(urls, loader);
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/lang/System;getProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"), index = 1)
    private String arclight$customMavenRepo(String defaultValue) {
        // Check for Luminara-specific property first
        String customRepo = System.getProperty(LUMINARA_MAVEN_REPO_PROPERTY);
        if (customRepo != null && !customRepo.trim().isEmpty()) {
            // Support multiple repositories separated by comma, use the first one
            String[] repos = customRepo.split(",");
            if (repos.length > 0) {
                String repo = repos[0].trim();
                if (!repo.isEmpty()) {
                    // Ensure URL ends with /
                    return repo.endsWith("/") ? repo : repo + "/";
                }
            }
        }
        return defaultValue;
    }
}
