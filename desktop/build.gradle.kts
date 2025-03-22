plugins {
    id("java")
    id("application")
    alias(libs.plugins.moduleplugin)
    alias(libs.plugins.javafxplugin)
    alias(libs.plugins.jlink)
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

application {
    mainModule = "su.nepom.budget.desktop"
    mainClass = "su.nepom.budget.desktop.BudgetApplication"
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml")
}

jlink {
    imageZip = project.file("${buildDir}/distributions/app-${javafx.platform.classifier}.zip")
    options = listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages")
    launcher {
        name = "budget"
    }

    mergedModule {
        requires ("kotlin.stdlib")
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":db-sqlite"))
    implementation(project(":events"))
    implementation(libs.fx.controlfx)
    implementation(libs.kotlin.coroutines)
    implementation(libs.logging.kotlin)

    testImplementation(libs.bundles.test.junit5)
    testImplementation(libs.logging.logback)
}

tasks.test {
    useJUnitPlatform()
}