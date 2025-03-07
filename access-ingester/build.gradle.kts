plugins {
    kotlin("jvm")
}

dependencies {
    runtimeOnly(libs.ucanaccess)
    implementation(libs.logging.logback)
    implementation(libs.logging.kotlin)
    implementation(project(":utils"))

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}