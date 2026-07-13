import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.3.1"
}

group = "me.hexedhero.pp"
version = "2.96.6-Folia"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.william278.net/releases")
}

dependencies {
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("com.github.NuVotifier:NuVotifier:2.7.2")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.william278.husksync:husksync-bukkit:3.8.7+1.21.8")
    compileOnly(files("sources/CrackShot.jar"))
    compileOnly("org.apache.commons:commons-lang3:3.20.0")

    implementation("org.bstats:bstats-bukkit:3.1.0")

    val sbLib = "2.7.4"
    implementation("net.megavex:scoreboard-library-api:$sbLib")
    runtimeOnly("net.megavex:scoreboard-library-implementation:$sbLib")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set(rootProject.name)

        relocate("org.bstats", "me.hexedhero.pp.shaded.bstats")
        relocate("net.megavex", "me.hexedhero.pp.shaded.scoreboardlibrary")
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }
}


java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

val decompileConfig = DecompileConfig(
    inputJar = "sources/PinataParty-2.69.6.jar",
    vineflowerVersion = "1.12.0",
    packageMappings = mapOf(
        "me/hexedhero/pp" to "."
    ),
    resourceMappings = mapOf(
        "plugin.yml" to ".",
        "config.yml" to ".",
        "default" to "."
    )
)

data class DecompileConfig(
    val inputJar: String = "sources/Template.jar",
    val vineflowerVersion: String = "1.12.0",
    val decompilerDir: String = "build/decompiler",
    val generatedDir: String = "sources/generated",
    val patchesDir: String = "patches",
    val packageMappings: Map<String, String> = emptyMap(),
    val resourceMappings: Map<String, String> = emptyMap(),
)

val decompilerDir = layout.buildDirectory.dir(decompileConfig.decompilerDir.removePrefix("build/"))
val inputJarFile = layout.projectDirectory.file(decompileConfig.inputJar)
val generatedOutputDir = layout.projectDirectory.dir(decompileConfig.generatedDir)
val patchesDirPath = layout.projectDirectory.dir(decompileConfig.patchesDir)

// Ephemeral working areas. These never get committed and can be deleted freely.
val patchWorkDir = project.projectDir.resolve(".patch-work")     // scratch git repo
val rejectsDir = project.projectDir.resolve(".patch-rejects")    // failed hunks land here

// Canonical layout used inside patches/ and the scratch repo. Patches mirror the
// real module layout, so a file at <module>/src/main/java/dev/foo/Bar.java is
// patched by patches/src/main/java/dev/foo/Bar.java.patch
val JAVA_ROOT = "src/main/java"
val RES_ROOT = "src/main/resources"

fun resolveModuleDir(moduleTarget: String): File? {
    if (moduleTarget == ".") return project.projectDir
    return try {
        project(":$moduleTarget").projectDir
    } catch (e: Exception) {
        null
    }
}

fun resolveModuleSrcDir(moduleTarget: String, packagePath: String): File? {
    val moduleDir = resolveModuleDir(moduleTarget) ?: return null
    return moduleDir.resolve("$JAVA_ROOT/$packagePath")
}

fun resolveModuleResourcesDir(moduleTarget: String): File? {
    val moduleDir = resolveModuleDir(moduleTarget) ?: return null
    return moduleDir.resolve(RES_ROOT)
}

// Always use forward slashes so canonical paths match what git emits in diffs,
// regardless of host OS.
fun File.invariantRelativeTo(base: File): String =
    this.relativeTo(base).path.replace('\\', '/')

// ============================================================================
// GIT OPERATIONS
// ============================================================================

abstract class GitOperationsService @Inject constructor(
    private val execOps: ExecOperations
) : BuildService<BuildServiceParameters.None> {

    private fun run(
        workingDir: File,
        vararg args: String,
        ignoreExit: Boolean = true,
        stdin: File? = null,
    ): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val result = execOps.exec {
            this.workingDir = workingDir
            commandLine(*args)
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = ignoreExit
            if (stdin != null) standardInput = stdin.inputStream()
        }
        return result.exitValue to out.toString()
    }

    fun setupRepo(workingDir: File) {
        run(workingDir, "git", "init")
        run(workingDir, "git", "config", "user.name", "Patcher")
        run(workingDir, "git", "config", "user.email", "patch@local")
        run(workingDir, "git", "config", "commit.gpgsign", "false")
        run(workingDir, "git", "config", "core.autocrlf", "false")
    }

    fun add(workingDir: File, path: String = "-A") = run(workingDir, "git", "add", path)

    fun commit(workingDir: File, message: String): Int =
        run(workingDir, "git", "commit", "--no-gpg-sign", "-q", "-m", message).first

    /** Paths (forward-slash, repo-relative) that differ between the index and HEAD. */
    fun changedPaths(workingDir: File): List<String> {
        val (_, out) = run(workingDir, "git", "diff", "--cached", "--no-renames", "--name-only")
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Writes a single-file unified diff (with git index headers) for [path]. */
    fun writeFileDiff(workingDir: File, path: String, outputFile: File) {
        outputFile.parentFile.mkdirs()
        outputFile.outputStream().use { stream ->
            execOps.exec {
                this.workingDir = workingDir
                commandLine("git", "diff", "--cached", "--no-renames", "--", path)
                standardOutput = stream
            }
        }
    }

    /** git apply with the given flags. Returns (exitCode, combinedOutput). */
    fun apply(
        workingDir: File,
        patchFile: File,
        threeWay: Boolean = false,
        reject: Boolean = false,
    ): Pair<Int, String> {
        val args = mutableListOf("git", "apply", "--whitespace=nowarn")
        if (threeWay) args.add("--3way")
        if (reject) args.add("--reject")
        args.add(patchFile.absolutePath)
        return run(workingDir, *args.toTypedArray())
    }

    /** Fuzzy fallback using the POSIX patch tool. Requires `patch` on PATH. */
    fun patchFuzzy(workingDir: File, patchFile: File, fuzz: Int): Pair<Int, String> = try {
        run(workingDir, "patch", "-p1", "--fuzz=$fuzz", "--no-backup-if-mismatch", "--forward", stdin = patchFile)
    } catch (e: Exception) {
        -1 to "patch tool unavailable: ${e.message}"
    }
}

val gitOpsService = gradle.sharedServices.registerIfAbsent("gitOps", GitOperationsService::class.java) {}

// ============================================================================
// DECOMPILE PIPELINE  (largely unchanged)
// ============================================================================

tasks.register("setupVineFlower") {
    doLast {
        decompilerDir.get().asFile.mkdirs()
        inputJarFile.asFile.parentFile.mkdirs()
        generatedOutputDir.asFile.mkdirs()

        if (!inputJarFile.asFile.exists()) {
            println("Input JAR not found at: ${decompileConfig.inputJar}. Nothing to decompile.")
            return@doLast
        }

        val pinnedVersion = decompileConfig.vineflowerVersion
        val expectedJarName = "vineflower-$pinnedVersion.jar"
        val vineflowerJar = decompilerDir.get().file(expectedJarName).asFile

        if (vineflowerJar.exists() && vineflowerJar.length() > 0) {
            println("✓ Vineflower $pinnedVersion already present: ${vineflowerJar.name}")
            return@doLast
        }

        decompilerDir.get().asFile.listFiles()
            ?.filter { it.name.startsWith("vineflower-") && it.name.endsWith(".jar") }
            ?.forEach {
                println("🗑 Removing stale ${it.name}")
                it.delete()
            }

        val downloadUrl = "https://github.com/Vineflower/vineflower/releases/download/$pinnedVersion/vineflower-$pinnedVersion.jar"
        println("Downloading Vineflower $pinnedVersion from $downloadUrl ...")

        try {
            URI(downloadUrl).toURL().openStream().use { input ->
                Files.copy(input, vineflowerJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            val slimJarName = "vineflower-$pinnedVersion-slim.jar"
            val slimUrl = "https://github.com/Vineflower/vineflower/releases/download/$pinnedVersion/$slimJarName"
            println("Plain jar not found, trying slim variant: $slimUrl")
            val slimJar = decompilerDir.get().file(slimJarName).asFile
            URI(slimUrl).toURL().openStream().use { input ->
                Files.copy(input, slimJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        println("✓ Vineflower $pinnedVersion ready")
    }
}

val decompile by tasks.registering(Exec::class) {
    dependsOn("setupVineFlower")

    inputs.file(inputJarFile)
    inputs.property("vineflowerVersion", decompileConfig.vineflowerVersion)
    outputs.dir(generatedOutputDir)

    doFirst {
        val pinnedVersion = decompileConfig.vineflowerVersion
        val vineflowerJar = decompilerDir.get().asFile.listFiles()
            ?.firstOrNull { it.name.startsWith("vineflower-$pinnedVersion") && it.name.endsWith(".jar") }
            ?: error("Vineflower $pinnedVersion jar not found. Run './gradlew setupVineFlower' first.")

        generatedOutputDir.asFile.mkdirs()

        commandLine(
            "java",
            "-jar",
            vineflowerJar.absolutePath,
            inputJarFile.asFile.absolutePath,
            generatedOutputDir.asFile.absolutePath
        )
    }
}

tasks.register("distributeSources") {
    dependsOn(decompile)

    doLast {
        val generatedDir = generatedOutputDir.asFile
        if (!generatedDir.exists()) {
            println("No generated sources found. Run 'decompile' task first.")
            return@doLast
        }

        println("Distributing decompiled sources to modules...")

        decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
            val sourcePackageDir = generatedDir.resolve(packagePath)
            if (!sourcePackageDir.exists()) {
                println("⚠ Package directory not found: $packagePath")
                return@forEach
            }
            val targetSrcDir = resolveModuleSrcDir(moduleTarget, packagePath)
            if (targetSrcDir == null) {
                println("❌ Module not found: $moduleTarget")
                return@forEach
            }

            println("📦 Copying $packagePath -> $moduleTarget")
            targetSrcDir.mkdirs()

            var fileCount = 0
            sourcePackageDir.walkTopDown().forEach { sourceFile ->
                if (sourceFile.isFile && sourceFile.extension == "java") {
                    val relativePath = sourceFile.relativeTo(sourcePackageDir)
                    val targetFile = targetSrcDir.resolve(relativePath)
                    targetFile.parentFile.mkdirs()
                    sourceFile.copyTo(targetFile, overwrite = true)
                    fileCount++
                }
            }
            println("✓ Copied $fileCount files to $moduleTarget")
        }

        if (decompileConfig.resourceMappings.isNotEmpty()) {
            val byModule = decompileConfig.resourceMappings.entries.groupBy({ it.value }, { it.key })
            byModule.forEach { (moduleTarget, resourceNames) ->
                println("\n📦 Copying resources to $moduleTarget...")
                val resourcesDir = resolveModuleResourcesDir(moduleTarget)
                if (resourcesDir == null) {
                    println("❌ Module not found: $moduleTarget")
                    return@forEach
                }
                resourcesDir.mkdirs()

                var resourceCount = 0
                resourceNames.forEach { resourceName ->
                    val sourceResource = generatedDir.resolve(resourceName)
                    if (sourceResource.exists()) {
                        val targetResource = resourcesDir.resolve(resourceName)
                        if (sourceResource.isDirectory) {
                            sourceResource.copyRecursively(targetResource, overwrite = true)
                            val count = sourceResource.walkTopDown().count { it.isFile }
                            println("  ✓ Copied directory: $resourceName ($count files)")
                            resourceCount += count
                        } else {
                            targetResource.parentFile.mkdirs()
                            sourceResource.copyTo(targetResource, overwrite = true)
                            println("  ✓ Copied file: $resourceName")
                            resourceCount++
                        }
                    } else {
                        println("  ⚠ Resource not found: $resourceName")
                    }
                }
                println("✓ Copied $resourceCount resource files to $moduleTarget")
            }
        }

        println("\n✓ Source distribution complete!")
    }
}

tasks.register("cleanDistributedSources") {
    doLast {
        println("Cleaning distributed sources from modules...")
        decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
            try {
                val targetSrcDir = resolveModuleSrcDir(moduleTarget, packagePath)
                if (targetSrcDir != null && targetSrcDir.exists()) {
                    targetSrcDir.deleteRecursively()
                    println("🗑 Cleaned $moduleTarget/$JAVA_ROOT/$packagePath")
                }
            } catch (e: Exception) {
                println("⚠ Could not clean $moduleTarget - ${e.message}")
            }
        }
        val byModule = decompileConfig.resourceMappings.entries.groupBy({ it.value }, { it.key })
        byModule.forEach { (moduleTarget, resourceNames) ->
            try {
                val resourcesDir = resolveModuleResourcesDir(moduleTarget) ?: return@forEach
                resourceNames.forEach { resourceName ->
                    val targetResource = resourcesDir.resolve(resourceName)
                    if (targetResource.exists()) {
                        if (targetResource.isDirectory) targetResource.deleteRecursively() else targetResource.delete()
                        println("🗑 Cleaned $moduleTarget/$RES_ROOT/$resourceName")
                    }
                }
            } catch (e: Exception) {
                println("⚠ Could not clean $moduleTarget resources - ${e.message}")
            }
        }
        println("✓ Clean complete!")
    }
}

tasks.register("cleanGenerated") {
    doLast {
        if (generatedOutputDir.asFile.exists()) {
            generatedOutputDir.asFile.deleteRecursively()
            println("🗑 Cleaned ${decompileConfig.generatedDir} (will be regenerated on next decompile)")
        }
    }
}

tasks.register("cleanPatchWork") {
    doLast {
        listOf(patchWorkDir, rejectsDir).forEach {
            if (it.exists()) {
                it.deleteRecursively()
                println("🗑 Cleaned ${it.name}")
            }
        }
    }
}

// ============================================================================
// SOURCE ENUMERATION  (the bridge between base / working / patch trees)
// ============================================================================

// A single tracked source file, identified by its canonical path. base lives in
// the pristine decompiled tree, working lives in the module sources.
data class SrcEntry(val canonical: String, val base: File, val working: File)

fun enumerateEntries(): List<SrcEntry> {
    val entries = mutableListOf<SrcEntry>()

    decompileConfig.packageMappings.forEach { (packagePath, moduleTarget) ->
        val basePkg = generatedOutputDir.asFile.resolve(packagePath)
        val workPkg = resolveModuleSrcDir(moduleTarget, packagePath) ?: return@forEach
        val rels = LinkedHashSet<String>()
        if (basePkg.exists()) basePkg.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { rels.add(it.invariantRelativeTo(basePkg)) }
        if (workPkg.exists()) workPkg.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { rels.add(it.invariantRelativeTo(workPkg)) }
        rels.forEach { rel ->
            entries.add(SrcEntry("$JAVA_ROOT/$packagePath/$rel", basePkg.resolve(rel), workPkg.resolve(rel)))
        }
    }

    decompileConfig.resourceMappings.forEach { (resourceName, moduleTarget) ->
        val baseRes = generatedOutputDir.asFile.resolve(resourceName)
        val workRoot = resolveModuleResourcesDir(moduleTarget) ?: return@forEach
        val workRes = workRoot.resolve(resourceName)
        val baseIsDir = baseRes.isDirectory
        val workIsDir = workRes.isDirectory
        if (baseIsDir || workIsDir) {
            val rels = LinkedHashSet<String>()
            if (baseRes.exists()) baseRes.walkTopDown().filter { it.isFile }
                .forEach { rels.add(it.invariantRelativeTo(baseRes)) }
            if (workRes.exists()) workRes.walkTopDown().filter { it.isFile }
                .forEach { rels.add(it.invariantRelativeTo(workRes)) }
            rels.forEach { rel ->
                entries.add(SrcEntry("$RES_ROOT/$resourceName/$rel", baseRes.resolve(rel), workRes.resolve(rel)))
            }
        } else {
            entries.add(SrcEntry("$RES_ROOT/$resourceName", baseRes, workRes))
        }
    }

    return entries
}

// Maps a canonical path (possibly for a brand-new file) back to where it should
// be written in the module tree.
fun canonicalToWorking(canonical: String): File? {
    when {
        canonical.startsWith("$JAVA_ROOT/") -> {
            val rel = canonical.removePrefix("$JAVA_ROOT/")
            val match = decompileConfig.packageMappings.entries
                .filter { rel == it.key || rel.startsWith("${it.key}/") }
                .maxByOrNull { it.key.length } ?: return null
            val moduleDir = resolveModuleDir(match.value) ?: return null
            return moduleDir.resolve("$JAVA_ROOT/$rel")
        }
        canonical.startsWith("$RES_ROOT/") -> {
            val rel = canonical.removePrefix("$RES_ROOT/")
            val match = decompileConfig.resourceMappings.entries
                .filter { rel == it.key || rel.startsWith("${it.key}/") }
                .maxByOrNull { it.key.length } ?: return null
            val resDir = resolveModuleResourcesDir(match.value) ?: return null
            return resDir.resolve(rel)
        }
        else -> return null
    }
}

// Lays out the pristine base into the scratch repo at canonical paths.
fun layoutBaseInto(scratch: File) {
    enumerateEntries().forEach { entry ->
        if (entry.base.exists() && entry.base.isFile) {
            val target = scratch.resolve(entry.canonical)
            target.parentFile.mkdirs()
            entry.base.copyTo(target, overwrite = true)
        }
    }
}

// Replaces the canonical content in the scratch repo with the current module
// (working) sources. Deletions in the module become deletions in the repo.
fun layoutWorkingInto(scratch: File) {
    scratch.resolve("src").deleteRecursively()
    enumerateEntries().forEach { entry ->
        if (entry.working.exists() && entry.working.isFile) {
            val target = scratch.resolve(entry.canonical)
            target.parentFile.mkdirs()
            entry.working.copyTo(target, overwrite = true)
        }
    }
}

fun freshScratch(): File {
    if (patchWorkDir.exists()) patchWorkDir.deleteRecursively()
    patchWorkDir.mkdirs()
    return patchWorkDir
}

fun allPatchFiles(): List<File> {
    val root = patchesDirPath.asFile
    if (!root.exists()) return emptyList()
    return root.walkTopDown().filter { it.isFile && it.extension == "patch" }.sortedBy { it.path }.toList()
}

fun patchCanonical(patchFile: File): String =
    patchFile.invariantRelativeTo(patchesDirPath.asFile).removeSuffix(".patch")

fun pruneEmptyDirs(root: File) {
    if (!root.exists()) return
    root.walkBottomUp()
        .filter { it.isDirectory && it != root }
        .forEach { if (it.listFiles()?.isEmpty() == true) it.delete() }
}

fun hasConflictMarkers(file: File): Boolean = try {
    file.readLines().any {
        it.startsWith("<<<<<<<") || it.startsWith("=======") || it.startsWith(">>>>>>>")
    }
} catch (e: Exception) { false }

// ============================================================================
// PATCH TASKS  (Paper-style: one patch per file, pristine base)
// ============================================================================

enum class ApplyResult { CLEAN, OFFSET, CONFLICT, REJECTED, FAILED }

// Shared apply routine. fuzzy=true inserts a POSIX `patch --fuzz` rung before
// falling back to writing .rej files.
fun runApply(fuzzy: Boolean) {
    val gitOps = gitOpsService.get()
    val patches = allPatchFiles()
    if (patches.isEmpty()) {
        println("ℹ️  No patches found under ${decompileConfig.patchesDir}/ — nothing to apply.")
        return
    }

    if (rejectsDir.exists()) rejectsDir.deleteRecursively()

    println("\n🔧 Applying ${patches.size} file patch(es)${if (fuzzy) " (fuzzy)" else ""}...")
    println("=".repeat(60))

    val scratch = freshScratch()
    gitOps.setupRepo(scratch)
    layoutBaseInto(scratch)
    gitOps.add(scratch)
    gitOps.commit(scratch, "Pristine decompiled base")

    val outcomes = LinkedHashMap<String, ApplyResult>()

    patches.forEach { patch ->
        val canonical = patchCanonical(patch)
        var (exit, _) = gitOps.apply(scratch, patch)
        var result = if (exit == 0) ApplyResult.CLEAN else null

        if (result == null) {
            val (e3, _) = gitOps.apply(scratch, patch, threeWay = true)
            if (e3 == 0) {
                val target = scratch.resolve(canonical)
                result = if (target.exists() && hasConflictMarkers(target)) ApplyResult.CONFLICT else ApplyResult.OFFSET
            }
        }

        if (result == null && fuzzy) {
            val (ef, _) = gitOps.patchFuzzy(scratch, patch, fuzz = 3)
            if (ef == 0) result = ApplyResult.OFFSET
        }

        if (result == null) {
            gitOps.apply(scratch, patch, reject = true)
            val rejected = scratch.walkTopDown().filter { it.name.endsWith(".rej") }.toList()
            if (rejected.isNotEmpty()) {
                rejected.forEach { rej ->
                    val rel = rej.invariantRelativeTo(scratch)
                    val dest = rejectsDir.resolve(rel)
                    dest.parentFile.mkdirs()
                    rej.copyTo(dest, overwrite = true)
                    rej.delete()
                }
                result = ApplyResult.REJECTED
            } else {
                result = ApplyResult.FAILED
            }
        }

        outcomes[canonical] = result!!
        val icon = when (result) {
            ApplyResult.CLEAN -> "✓"
            ApplyResult.OFFSET -> "≈"
            ApplyResult.CONFLICT -> "⚠"
            ApplyResult.REJECTED -> "✗"
            ApplyResult.FAILED -> "✗"
        }
        println("  $icon ${result.name.lowercase().padEnd(8)} $canonical")
    }

    // Copy results (including conflict-marked files) back into the modules.
    println("\n📦 Writing patched files into module sources...")
    patches.forEach { patch ->
        val canonical = patchCanonical(patch)
        val working = canonicalToWorking(canonical) ?: return@forEach
        val fromScratch = scratch.resolve(canonical)
        if (fromScratch.exists()) {
            working.parentFile.mkdirs()
            fromScratch.copyTo(working, overwrite = true)
        } else if (working.exists()) {
            working.delete() // patch deleted this file
        }
    }

    patchWorkDir.deleteRecursively()

    val clean = outcomes.values.count { it == ApplyResult.CLEAN }
    val offset = outcomes.values.count { it == ApplyResult.OFFSET }
    val needsHelp = outcomes.filterValues { it == ApplyResult.CONFLICT || it == ApplyResult.REJECTED || it == ApplyResult.FAILED }

    println("\n" + "=".repeat(60))
    println("✨ clean: $clean | offset: $offset | needs attention: ${needsHelp.size}")

    if (needsHelp.isNotEmpty()) {
        println("\n👋 Human intervention needed — these did not apply cleanly:")
        needsHelp.forEach { (canonical, res) ->
            val working = canonicalToWorking(canonical)
            when (res) {
                ApplyResult.CONFLICT -> println("  ⚠ conflict markers in: ${working ?: canonical}")
                ApplyResult.REJECTED -> println("  ✗ rejected hunks:       .patch-rejects/$canonical.rej  (partial result in module)")
                else -> println("  ✗ failed:                $canonical")
            }
        }
        println()
        println("  1) Open the files above in your IDE and resolve the markers / apply the .rej hunks.")
        println("  2) Run './gradlew rebuildFilePatches' to regenerate clean patches from your fixes.")
        if (!fuzzy) println("  3) Or retry with fuzzier matching: './gradlew applyFilePatchesFuzzy'")
    } else {
        println("ℹ️  All patches applied without conflicts.")
    }
}

val applyFilePatches = tasks.register("applyFilePatches") {
    group = "patching"
    description = "Applies every per-file patch to the pristine base, writing results into the module sources."
    usesService(gitOpsService)
    dependsOn(decompile) // regenerate the base on demand if sources/generated was deleted
    doLast { runApply(fuzzy = false) }
}

tasks.register("applyFilePatchesFuzzy") {
    group = "patching"
    description = "Like applyFilePatches but adds a fuzzy `patch` rung before rejecting hunks."
    usesService(gitOpsService)
    dependsOn(decompile)
    doLast { runApply(fuzzy = true) }
}

tasks.register("rebuildFilePatches") {
    group = "patching"
    description = "Regenerates per-file patches by diffing the module sources against the pristine base. " +
            "Updates changed patches, removes patches for files that no longer differ."
    usesService(gitOpsService)
    dependsOn(decompile)

    doLast {
        val gitOps = gitOpsService.get()
        println("\n🔁 Rebuilding file patches against pristine base...")
        println("=".repeat(60))

        val scratch = freshScratch()
        gitOps.setupRepo(scratch)
        layoutBaseInto(scratch)
        gitOps.add(scratch)
        gitOps.commit(scratch, "Pristine decompiled base")

        layoutWorkingInto(scratch)
        gitOps.add(scratch)

        val changed = gitOps.changedPaths(scratch)
        val patchesRoot = patchesDirPath.asFile

        val tmp = scratch.resolve(".rebuild.tmp")
        var created = 0
        var updated = 0
        var unchanged = 0

        changed.forEach { canonical ->
            gitOps.writeFileDiff(scratch, canonical, tmp)
            val fresh = if (tmp.exists()) tmp.readBytes() else ByteArray(0)
            val patchFile = patchesRoot.resolve("$canonical.patch")
            when {
                !patchFile.exists() -> {
                    patchFile.parentFile.mkdirs()
                    tmp.copyTo(patchFile, overwrite = true)
                    println("  ➕ created:  $canonical.patch")
                    created++
                }
                !patchFile.readBytes().contentEquals(fresh) -> {
                    tmp.copyTo(patchFile, overwrite = true)
                    println("  ✏️  updated:  $canonical.patch")
                    updated++
                }
                else -> unchanged++ // identical diff — leave the file untouched
            }
        }

        // Prune patches whose file no longer differs from base.
        val changedSet = changed.toSet()
        var removed = 0
        allPatchFiles().forEach { patch ->
            val canonical = patchCanonical(patch)
            if (canonical !in changedSet) {
                patch.delete()
                println("  🗑 removed:  $canonical.patch")
                removed++
            }
        }
        pruneEmptyDirs(patchesRoot)
        patchWorkDir.deleteRecursively()

        println("\n" + "=".repeat(60))
        println("✨ $created created, $updated updated, $removed removed  ($unchanged unchanged)")
    }
}

tasks.register("listPatches") {
    group = "patching"
    description = "Lists the per-file patches currently in the patch tree."
    doLast {
        val patches = allPatchFiles()
        if (patches.isEmpty()) {
            println("ℹ️  No patches under ${decompileConfig.patchesDir}/")
            return@doLast
        }
        println("\n📋 File patches (${patches.size})")
        println("=".repeat(60))
        patches.forEach { patch ->
            val lines = patch.readLines()
            val add = lines.count { it.startsWith("+") && !it.startsWith("+++") }
            val del = lines.count { it.startsWith("-") && !it.startsWith("---") }
            println("📄 ${patchCanonical(patch)}  (+$add -$del)")
        }
        println("=".repeat(60))
    }
}

tasks.register("patchStatus") {
    group = "patching"
    description = "Shows whether the patch tree is in sync with the module sources (i.e. what rebuildFilePatches would change)."
    usesService(gitOpsService)
    dependsOn(decompile)
    doLast {
        val gitOps = gitOpsService.get()
        println("\n📊 Patch status (patch tree vs module sources)")
        println("=".repeat(60))

        // Reproduce exactly what rebuildFilePatches would compute, but write nothing.
        val scratch = freshScratch()
        gitOps.setupRepo(scratch)
        layoutBaseInto(scratch)
        gitOps.add(scratch)
        gitOps.commit(scratch, "Pristine decompiled base")

        layoutWorkingInto(scratch)
        gitOps.add(scratch)

        val changed = gitOps.changedPaths(scratch)
        val changedSet = changed.toSet()
        val tmp = scratch.resolve(".status.tmp")

        var newCount = 0
        var updated = 0
        var tracked = 0
        var stale = 0

        // For each file that differs from base, compare the diff rebuild *would*
        // write against the patch currently on disk.
        changed.forEach { canonical ->
            gitOps.writeFileDiff(scratch, canonical, tmp)
            val freshDiff = if (tmp.exists()) tmp.readBytes() else ByteArray(0)
            val existing = patchesDirPath.asFile.resolve("$canonical.patch")
            when {
                !existing.exists() -> {
                    println("  ➕ new patch:    $canonical")
                    newCount++
                }
                !existing.readBytes().contentEquals(freshDiff) -> {
                    println("  ✏️  updated:      $canonical")
                    updated++
                }
                else -> tracked++
            }
        }

        // Patches whose file no longer differs from base would be pruned.
        allPatchFiles().forEach { patch ->
            val canonical = patchCanonical(patch)
            if (canonical !in changedSet) {
                println("  🗑️  stale:        $canonical (would be removed)")
                stale++
            }
        }

        patchWorkDir.deleteRecursively()

        println("\n" + "=".repeat(60))
        println("Summary: $newCount new, $updated updated, $stale stale  ($tracked already in sync)")
        if (newCount + updated + stale > 0) {
            println("\n💡 Run './gradlew rebuildFilePatches' to sync the patch tree.")
        } else {
            println("\nℹ️  Patch tree is in sync — rebuildFilePatches would make no changes.")
        }
    }
}

tasks.register("inspectDecompiledStructure") {
    dependsOn(decompile)
    doLast {
        val generatedDir = generatedOutputDir.asFile
        if (!generatedDir.exists()) {
            println("❌ No generated sources found. Run 'decompile' task first.")
            return@doLast
        }
        println("\n📂 Inspecting decompiled package structure...")
        println("=".repeat(60))
        generatedDir.listFiles()?.filter { it.isDirectory }?.sorted()?.forEach { dir ->
            fun printTree(file: File, prefix: String, depth: Int) {
                if (depth > 4) return
                val javaCount = file.walkTopDown().count { it.isFile && it.extension == "java" }
                if (javaCount == 0) return
                println("$prefix📦 ${file.relativeTo(generatedDir)} ($javaCount files)")
                file.listFiles()?.filter { it.isDirectory }?.sorted()?.forEach { child ->
                    printTree(child, "$prefix   ", depth + 1)
                }
            }
            printTree(dir, "", 0)
        }
        val resources = generatedDir.listFiles()?.filter { !it.isDirectory || it.name !in listOf("com", "org", "net") }
        if (resources != null && resources.isNotEmpty()) {
            println("\n📋 Resources at root:")
            resources.sorted().forEach { res ->
                if (res.isDirectory) {
                    val count = res.walkTopDown().count { it.isFile }
                    println("  📁 ${res.name}/ ($count files)")
                } else {
                    println("  📄 ${res.name}")
                }
            }
        }
        println("\n" + "=".repeat(60))
        println("💡 Use this to configure packageMappings and resourceMappings")
    }
}

// ============================================================================
// AGGREGATE / CONVENIENCE TASKS
// ============================================================================

// Order distribute before apply so patched files overwrite the pristine copies.
tasks.named("applyFilePatches") { mustRunAfter("distributeSources") }

tasks.register("applyPatches") {
    group = "patching"
    description = "Full reconstruct: distribute the pristine base into modules, then overlay all file patches."
    dependsOn("distributeSources", "applyFilePatches")
}

tasks.register("rebuildPatches") {
    group = "patching"
    description = "Alias for rebuildFilePatches."
    dependsOn("rebuildFilePatches")
}

tasks.register("setup") {
    group = "patching"
    description = "First-time / clean setup: decompile, distribute, and apply all patches."
    dependsOn("applyPatches")
    doLast { println("\n✨ Setup complete — module sources reflect base + patches.") }
}

tasks.register("resetSources") {
    group = "patching"
    description = "Discards manual module edits and rebuilds the working tree from base + patches."
    dependsOn("cleanDistributedSources", "applyPatches")
    tasks.named("applyPatches").get().mustRunAfter("cleanDistributedSources")
}

tasks.register<Delete>("cleanCache") {
    group = "build"
    description = "Delete ephemeral patch working directories."
    delete(patchWorkDir, rejectsDir)
}