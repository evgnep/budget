plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(libs.jackson.yaml)
    api(libs.jackson.kotlin)
    api(libs.kotlin.datetime)
    implementation(libs.kotlin.coroutines)
    implementation(libs.kotlin.serialization.core)
    implementation(libs.logging.kotlin)

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(libs.bundles.test.junit5)
}

tasks.test {
    useJUnitPlatform()
}