plugins {
    id("kotlin-kapt")
    id("java")
    id("application")
    alias(libs.plugins.javafxplugin)
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

dependencies {
    implementation(project(":common"))
    implementation(project(":db-sqlite"))
    implementation(project(":events"))
    implementation(libs.dagger.lib)
    implementation(libs.fx.controls)
    implementation(libs.fx.validation)
    implementation(libs.kotlin.coroutines)
    implementation(libs.logging.kotlin)
    implementation(libs.logging.logback)

    kapt(libs.dagger.compiler)

    testImplementation(libs.bundles.test.junit5)
}

tasks.test {
    useJUnitPlatform()
}