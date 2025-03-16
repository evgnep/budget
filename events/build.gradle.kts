plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.kotlin.serialization.json)
    api(project(":common"))

    testImplementation(libs.bundles.test.junit5)
}

tasks.test {
    useJUnitPlatform()
}