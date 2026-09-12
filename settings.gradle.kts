rootProject.name = "budget"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    val kotlinVer = "2.4.0"
    kotlin("jvm") version kotlinVer apply false
    kotlin("plugin.serialization") version kotlinVer apply false
    kotlin("plugin.compose") version kotlinVer apply false
    kotlin("kapt") version kotlinVer apply false
    // Kept on the root classpath so it shares a classloader with the Kotlin
    // plugin - the :android module applies it without a version.
    id("com.android.application") version "8.12.3" apply false
    id("com.android.library") version "8.12.3" apply false
}

include(
    "access-ingester",
    "common",
    "db-sqlite",
    "desktop",
    "events",
)
