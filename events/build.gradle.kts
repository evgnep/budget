plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":common"))
    implementation(libs.kotlin.coroutines)

    implementation(libs.kotlin.serialization.json)
    implementation(libs.logging.kotlin)

    testImplementation(libs.bundles.test.junit5)
    testImplementation(libs.logging.logback)
}

tasks.test {
    useJUnitPlatform()
}