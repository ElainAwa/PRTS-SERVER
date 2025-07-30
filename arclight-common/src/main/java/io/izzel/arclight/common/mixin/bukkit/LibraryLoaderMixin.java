package io.izzel.arclight.common.mixin.bukkit;

import io.izzel.arclight.common.mod.util.remapper.generated.RemappingURLClassLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.URL;
import java.net.URLClassLoader;

@Mixin(targets = "org.bukkit.plugin.java.LibraryLoader", remap = false)
public class LibraryLoaderMixin {

    private static final String LUMINARA_MAVEN_REPO_PROPERTY = "luminara.maven.repo";
    private static final String BUKKIT_REPO_PROPERTY = "org.bukkit.plugin.java.LibraryLoader.centralURL";

    static {
        String customRepo = System.getProperty(LUMINARA_MAVEN_REPO_PROPERTY);
        if (customRepo != null && !customRepo.trim().isEmpty()) {
            String[] repos = customRepo.split(",");
            if (repos.length > 0) {
                String repo = repos[0].trim();
                if (!repo.isEmpty()) {
                    String repoUrl = repo.endsWith("/") ? repo : repo + "/";
                    System.setProperty(BUKKIT_REPO_PROPERTY, repoUrl);
                }
            }
        }
    }

    @Redirect(method = "createLoader", at = @At(value = "NEW", target = "java/net/URLClassLoader"))
    private URLClassLoader arclight$useRemapped(URL[] urls, ClassLoader loader) {
        return new RemappingURLClassLoader(urls, loader);
    }


}
