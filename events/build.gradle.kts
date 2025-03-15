plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.datetime)
    implementation(project(":utils"))
    api(project(":model"))

    testImplementation(libs.bundles.test.junit5)
}

tasks.test {
    useJUnitPlatform()
}