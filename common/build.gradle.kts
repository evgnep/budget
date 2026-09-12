plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(libs.kotlin.datetime)
    implementation(libs.kotlin.coroutines)
    implementation(libs.kotlin.serialization.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.logging.kotlin)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(project(":events"))
    testImplementation(libs.bundles.test.junit5)
    testImplementation(libs.kotlin.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}