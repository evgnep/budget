plugins {
    kotlin("jvm")
}

dependencies {
    api(libs.jackson.yaml)
    api(libs.jackson.kotlin)
    api(libs.kotlin.datetime)

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}