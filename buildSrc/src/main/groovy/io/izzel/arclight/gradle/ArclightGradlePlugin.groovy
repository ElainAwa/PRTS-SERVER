package io.izzel.arclight.gradle

import io.izzel.arclight.gradle.extension.ArclightExtension
import io.izzel.arclight.gradle.runnable.FileDownloader
import io.izzel.arclight.gradle.runnable.SpigotBuilder
import io.izzel.arclight.gradle.tasks.ProcessMappingTask
import io.izzel.arclight.gradle.tasks.RemapSpigotTask
import io.izzel.arclight.gradle.tasks.RenameJarTask
import net.fabricmc.loom.LoomGradlePlugin
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.apache.commons.io.IOUtils

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ArclightGradlePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        def arclight = project.extensions.create('arclight', ArclightExtension, project)

        def arclightRepo = arclight.cacheDir.resolve('arclight_repo')
        project.repositories.maven {
            name = 'Arclight Spigot Repo'
            url = arclightRepo
        }

        def mappingsDir = arclight.cacheDir.resolve('arclight_cache/mappings')
        def forgeMappings = mappingsDir.resolve('bukkit_srg.srg').toFile()
        def forgeInheritance = mappingsDir.resolve('inheritanceMap.txt').toFile()
        def reobfMappings = mappingsDir.resolve('reobf_bukkit.srg').toFile()
        def neoforgeMappings = mappingsDir.resolve('bukkit_moj.srg').toFile()
        def fabricMappings = mappingsDir.resolve('bukkit_intermediary.srg').toFile()
        def fabricInheritance = mappingsDir.resolve('inheritanceMap_intermediary.txt').toFile()
        arclight.mappingsConfiguration.bukkitToForge = forgeMappings
        arclight.mappingsConfiguration.reobfBukkitPackage = reobfMappings
        arclight.mappingsConfiguration.bukkitToForgeInheritance = forgeInheritance
        arclight.mappingsConfiguration.bukkitToNeoForge = neoforgeMappings
        arclight.mappingsConfiguration.bukkitToFabric = fabricMappings
        arclight.mappingsConfiguration.bukkitToFabricInheritance = fabricInheritance

        project.tasks.register('relocateCraftBukkit', RenameJarTask) {
            it.dependsOn project.tasks.remapJar
            inputJar.set project.tasks.remapJar.archiveFile
            archiveClassifier.set 'relocated'
            mappings = arclight.mappingsConfiguration.reobfBukkitPackage
        }
        project.tasks.build.dependsOn('relocateCraftBukkit')

        project.afterEvaluate {
            setupSpigot(project, arclightRepo)
        }
    }

    private static def setupSpigot(Project project, Path arclightRepo) {
        def arclight = project.extensions.getByName('arclight') as ArclightExtension

        // [local-patch] skip Spigot fetch: hub.spigotmc.org returns 522 in this env; neoforgeJar needs no Spigot mappings
        if (Boolean.getBoolean('arclight.skipSpigot')) {
            project.logger.lifecycle(":spigot skipped via -Darclight.skipSpigot (local build only, not committed)")
            return
        }

        def mappingsDir = arclight.cacheDir.resolve('arclight_cache/mappings')

        def spigotDeps = arclightRepo.resolve("io/izzel/arclight/generated/spigot/${arclight.mcVersion}")
        def spigotMapped = spigotDeps.resolve("spigot-${arclight.mcVersion}-mapped.jar")
        def spigotDeobf = spigotDeps.resolve("spigot-${arclight.mcVersion}-deobf.jar")

        def buildMeta = arclight.cacheDir.resolve('spigot_version.json')
        def rev = arclight.mcVersion
        if (arclight.spigotReversion) {
            rev = arclight.spigotReversion
        }

        project.logger.lifecycle("Setup for Spigot ${arclight.mcVersion}(${arclight.spigotReversion})")
        def newBuildMeta = IOUtils.toString(new URI("https://hub.spigotmc.org/versions/${rev}.json").toURL(), StandardCharsets.UTF_8)
        if (Files.exists(buildMeta)) {
            var built = Files.readString(buildMeta)
            if (built == newBuildMeta) {
                if (arclight.mappingsConfiguration.areMappingsExist()
                        && Files.exists(spigotDeobf)) {
                    project.logger.lifecycle(":spigot build cache valid, using it")
                    project.logger.debug(built)
                    return
                }
            }
        }

        def buildSpigotWorkDir = arclight.cacheDir.resolve('arclight_cache/buildtools')

        // [local-patch] Reuse an already-prepared buildtools workDir when present.
        // On this machine jgit's ResetCommand (BuildTools' internal pull) deadlocks in
        // WinNTFileSystem.delete0 (removeEmptyParents) during working-tree checkout.
        // Workaround: pre-checkout all 4 repos to their exact target commits with NATIVE git,
        // then skip the wipe/clone so BuildTools' jgit reset becomes a no-op (no file deletes).
        def wd = buildSpigotWorkDir.toFile()
        def buildToolsJar = buildSpigotWorkDir.resolve('BuildTools.jar')
        def reposReady = new File(wd, 'BuildData/.git').isDirectory() &&
                new File(wd, 'Bukkit/.git').isDirectory() &&
                new File(wd, 'CraftBukkit/.git').isDirectory() &&
                new File(wd, 'Spigot/.git').isDirectory() &&
                buildToolsJar.toFile().isFile()
        if (reposReady) {
            project.logger.lifecycle(":REUSING pre-prepared buildtools workDir (skip wipe/clone to avoid jgit delete0 deadlock)")
            def stagedMaven = new File(System.getProperty('user.home'), '.gradle/staged-maven/apache-maven-3.9.6')
            def targetMaven = new File(wd, 'apache-maven-3.9.6')
            if (!targetMaven.isDirectory() && stagedMaven.isDirectory()) {
                project.logger.lifecycle(":pre-staging local Maven ${stagedMaven} -> ${targetMaven}")
                FileUtils.copyDirectory(stagedMaven, targetMaven)
            }
        } else {
            FileUtils.deleteDirectory(wd)
            Files.createDirectories(buildSpigotWorkDir)

            // [local-patch] pre-stage Maven so BuildTools skips the slow archive.apache.org download
            def stagedMaven = new File(System.getProperty('user.home'), '.gradle/staged-maven/apache-maven-3.9.6')
            if (stagedMaven.isDirectory()) {
                def targetMaven = new File(wd, 'apache-maven-3.9.6')
                project.logger.lifecycle(":pre-staging local Maven ${stagedMaven} -> ${targetMaven}")
                FileUtils.copyDirectory(stagedMaven, targetMaven)
            } else {
                project.logger.lifecycle(":no staged Maven at ${stagedMaven}, BuildTools will download it")
            }

            project.logger.lifecycle(":step1 download build tools")
            def downloadBuildTools = new FileDownloader("https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar", buildToolsJar)
            downloadBuildTools.run()
        }

        project.logger.lifecycle(":step2 build spigot")
        def spigotBuilder = project.getObjects().newInstance(SpigotBuilder)
        spigotBuilder.buildToolsJar = buildToolsJar
        spigotBuilder.workDir = buildSpigotWorkDir
        spigotBuilder.outputDir = spigotDeps
        spigotBuilder.minecraftVersion = arclight.mcVersion
        spigotBuilder.reversion = arclight.spigotReversion
        spigotBuilder.run()

        new LocalMavenHelper("io.izzel.arclight.generated", "spigot", arclight.mcVersion, null, arclightRepo).savePom()

        project.logger.lifecycle(":step3 process mappings")
        def processMapping = new ProcessMappingTask(project)
        processMapping.buildData = new File(buildSpigotWorkDir.toFile(), 'BuildData')
        processMapping.mcVersion = arclight.mcVersion
        processMapping.bukkitVersion = arclight.bukkitVersion
        processMapping.outDir = mappingsDir.toFile()
        processMapping.inJar = spigotBuilder.outputJar.toFile()
        processMapping.run()

        project.logger.lifecycle(":step4 remap spigot jar")
        def remapSpigot = new RemapSpigotTask(project)
        remapSpigot.ssJar = new File(buildSpigotWorkDir.toFile(), 'BuildData/bin/SpecialSource.jar')
        remapSpigot.inJar = spigotBuilder.outputJar.toFile()
        remapSpigot.inSrg = new File(processMapping.outDir, 'bukkit_srg.srg')
        remapSpigot.inSrgToStable = new File(processMapping.outDir, "srg_to_named.srg")
        remapSpigot.inheritanceMap = new File(processMapping.outDir, 'inheritanceMap.txt')
        remapSpigot.outJar = project.file(spigotMapped)
        remapSpigot.outDeobf = project.file(spigotDeobf)
        remapSpigot.inAt = arclight.accessTransformer
        remapSpigot.bukkitVersion = arclight.bukkitVersion
        remapSpigot.inExtraSrg = arclight.extraMapping
        remapSpigot.run()

        Files.writeString(buildMeta, newBuildMeta)
    }
}
