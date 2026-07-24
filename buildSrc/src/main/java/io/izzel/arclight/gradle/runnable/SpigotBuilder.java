package io.izzel.arclight.gradle.runnable;

import io.izzel.arclight.gradle.api.extension.IArclightSpigotExtension;
import io.izzel.arclight.gradle.util.GitOps;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.gradle.api.GradleException;
import org.gradle.process.ExecOperations;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.jar.JarFile;

public class SpigotBuilder implements Runnable {

    private final ExecOperations execOperations;

    @Getter
    @Setter
    private Path buildToolsJar;

    @Getter
    @Setter
    private Path workDir;

    @Getter
    @Setter
    private Path outputDir;

    /**
     * Minecraft version to build spigot.
     */
    @Getter
    @Setter
    private String minecraftVersion;

    /**
     * Specific build number of SpigotMC jenkins.
     */
    @Getter
    @Setter
    @Nullable
    private String reversion = null;

    /**
     * The specific commit refs.
     * Todo: add a task to use it.
     */
    @Getter
    @Setter
    @Nullable
    private IArclightSpigotExtension extension;

    @Getter
    @Setter
    private boolean forceRebuild = false;

    /**
     * Remove the work dir.
     */
    @Getter
    @Setter
    private boolean refreshCache = false;

    @Getter
    private Path outputJar;

    @Inject
    public SpigotBuilder(ExecOperations execOperations) {
        this.execOperations = execOperations;
    }

    @SneakyThrows
    @Override
    public void run() {
        Objects.requireNonNull(buildToolsJar);
        Objects.requireNonNull(workDir);
        Objects.requireNonNull(outputDir);
        Objects.requireNonNull(minecraftVersion);

        this.outputJar = outputDir.resolve("spigot-" + minecraftVersion + ".jar");

        if (forceRebuild) {
            Files.delete(outputDir);
        }

        Files.createDirectories(outputDir);

        if (Files.exists(workDir)) {
            if (refreshCache) {
                Files.delete(workDir);
            }
        }

        Files.createDirectories(workDir);

        if (extension != null) {
            checkout("Bukkit", "https://hub.spigotmc.org/stash/scm/spigot/bukkit.git", extension.getBukkitRef());
            checkout("CraftBukkit", "https://hub.spigotmc.org/stash/scm/spigot/craftbukkit.git", extension.getCraftBukkitRef());
            checkout("Spigot", "https://hub.spigotmc.org/stash/scm/spigot/spigot.git", extension.getSpigotRef());
            checkout("BuildData", "https://hub.spigotmc.org/stash/scm/spigot/builddata.git", extension.getBuildDataRef());
        }

        var spigot = workDir.resolve("spigot-" + minecraftVersion + ".jar");

        // [local-patch] If a prebuilt spigot bundler jar is already present in workDir
        // (e.g. downloaded from getbukkit.org to BYPASS BuildTools' jgit deadlock on this
        // Windows machine — the filesystem filter driver blocks dir deletions that jgit's
        // checkout -f triggers in WinNTFileSystem.delete0), skip the BuildTools invocation
        // entirely and just extract the inner spigot jar from the bundler.
        boolean skipBuildTools = Files.exists(spigot);

        if (!skipBuildTools) {
            var exit = execOperations.exec(spec -> {
                spec.setWorkingDir(workDir.toFile());
                spec.setStandardOutput(System.out);

                var rev = minecraftVersion;
                if (reversion != null) {
                    rev = reversion;
                }

                if (extension == null) {
                    // [local-patch] Use --dev <minecraftVersion> --dont-update to BYPASS BuildTools'
                    // jgit fetch/checkout, which deadlocks in WinNTFileSystem.delete0
                    // (DirCacheCheckout.removeEmptyParents) on this Windows machine — a filesystem
                    // filter driver blocks directory deletions, and jgit's checkout -f unconditionally
                    // rewrites/recreates files. The 4 Spigot repos are pre-checked-out to the exact
                    // 1.21.1 build 4344 target commits via NATIVE git and reused via
                    // ArclightGradlePlugin's reposReady branch (skips wipe/clone). BuildTools'
                    // patch-application phase uses NATIVE git (works here), so --dont-update is safe.
                    // NOTE: --rev + --dont-update is rejected by BuildTools ("makes no sense"); --dev is
                    // the only way to combine a version with --dont-update. The output jar name
                    // (spigot-<minecraftVersion>.jar) is unchanged.
                    spec.setCommandLine("java", "-jar", buildToolsJar.normalize().toString(), "--dev", minecraftVersion, "--dont-update");
                } else {
                    spec.setCommandLine("java", "-jar", buildToolsJar.normalize().toString(), "--dont-update");
                }

                spec.setIgnoreExitValue(true);
            }).getExitValue();

            if (exit == 0) {
                if (Files.exists(outputJar)) {
                    Files.delete(outputJar);
                }
            } else if (exit == 2) {
                return;
                // No changes.
            } else {
                throw new GradleException("Failed to build spigot jar.");
            }
        } else {
            System.out.println("[Luminara-Spigot] prebuilt bundler found at " + spigot
                    + " -> SKIP BuildTools (jgit deadlock workaround)");
            if (Files.exists(outputJar)) {
                Files.delete(outputJar);
            }
        }
        var bundler = outputDir.resolve("spigot-" + minecraftVersion + "-bundler.jar");
        if (Files.exists(spigot)) {
            Files.copy(spigot, bundler, StandardCopyOption.REPLACE_EXISTING);
            try (var jar = new JarFile(bundler.toFile())) {
                jar.stream().filter(e -> e.getName().startsWith("META-INF/versions/") && e.getName().endsWith(".jar"))
                        .limit(1)
                        .forEach(e -> {
                            try (var out = Files.newOutputStream(outputJar)) {
                                jar.getInputStream(e).transferTo(out);
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        });
            }
        }
    }

    @SneakyThrows
    private void checkout(String dirName, String url, String refs) {
        var repo = workDir.resolve(dirName);

        if (!GitOps.isGitRepo(repo)) {
            Files.delete(repo);

            execOperations.exec(spec -> {
                spec.setWorkingDir(workDir);
                spec.setStandardOutput(System.out);
                spec.setCommandLine(GitOps.clone(repo, url));
            });
        }

        execOperations.exec(spec -> {
            spec.setWorkingDir(refs);
            spec.setStandardOutput(System.out);
            spec.setCommandLine(GitOps.checkout(refs));
        });
    }
}
