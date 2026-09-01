import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    id("kotlin-kapt")
    id("java")
    id("application")
    alias(libs.plugins.javafxplugin)
    alias(libs.plugins.runtime)
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

application {
    mainClass = "su.nepom.budget.desktop.BudgetApplicationKt"
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml")
}

// Writes build-info.properties (version, git revision, dirty flag, build time) into resources.
// Read at runtime by su.nepom.budget.desktop.BuildInfo and shown in the "About" window.
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildInfo")
    val projectVersion = project.version.toString()
    val repoDir = rootDir
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }

    doLast {
        fun git(vararg args: String): String = runCatching {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
        }.getOrDefault("")

        val revision = git("rev-parse", "--short=10", "HEAD").ifEmpty { "unknown" }
        // untracked files (local sqlite, logs, ...) are ignored on purpose
        val dirty = git("status", "--porcelain", "--untracked-files=no").isNotEmpty()
        val buildTime = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())

        val file = outputDir.get().file("build-info.properties").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            version=$projectVersion
            revision=$revision
            dirty=$dirty
            buildTime=$buildTime
            """.trimIndent() + "\n"
        )
    }
}

tasks.processResources {
    from(generateBuildInfo)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":db-sqlite"))
    implementation(project(":events"))
    implementation(libs.dagger.lib)
    implementation(libs.fx.controls)
    implementation(libs.fx.validation)
    implementation(libs.kotlin.coroutines)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.logging.kotlin)
    implementation(libs.logging.logback)

    kapt(libs.dagger.compiler)

    testImplementation(libs.bundles.test.junit5)
}

tasks.test {
    useJUnitPlatform()
}

// Packaging: self-contained image with a trimmed JRE, no installer.
// Build: `gradle :desktop:jpackageImage` -> desktop/build/jpackage/budget/
// Copy that folder to the target machine and run `budget.exe` (native GUI launcher,
// no console window). To update, replace jars in `app/`.
runtime {
    options.set(listOf("--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-6"))

    // JVM modules needed by non-modular deps (sqlite-jdbc, logback, flyway, kotlin, ...).
    // run `gradle :desktop:suggestModules` to review this list.
    modules.set(
        listOf(
            "java.base",
            "java.desktop",
            "java.logging",
            "java.management",
            "java.naming",
            "java.scripting",
            "java.sql",
            "java.xml",
            "jdk.crypto.ec",
            "jdk.unsupported",
        )
    )

    imageDir.set(layout.buildDirectory.dir("budget"))
    imageZip.set(layout.buildDirectory.file("budget-${project.version}.zip"))

    jpackage {
        imageName = "budget"
        // app-image only, no setup.exe; launcher has no --win-console -> runs windowless
        skipInstaller = true
        imageOptions = listOf("--icon", "src/main/resources/icons/app.ico")
    }
}