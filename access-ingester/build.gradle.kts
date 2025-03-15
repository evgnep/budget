plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.kotlin.datetime)
    implementation(libs.ktorm.core)
    implementation(libs.logging.logback)
    implementation(libs.logging.kotlin)
    implementation(libs.sqlite)
    implementation(project(":events"))
    implementation(project(":utils"))

    runtimeOnly(libs.ktorm.sqlite)
    runtimeOnly(libs.ucanaccess)

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}
