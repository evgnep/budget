plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.flyway.core)
    implementation(libs.kotlin.datetime)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.ktorm.core)
    implementation(libs.ktorm.sqlite)
    implementation(libs.logging.logback)
    implementation(libs.logging.kotlin)
    implementation(libs.sqlite)
    implementation(project(":common"))

    testImplementation(libs.bundles.test.junit5)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}
