rootProject.name = "budget"

pluginManagement {

}

plugins {
    val kotlinVer = "2.1.10"
    kotlin("jvm") version kotlinVer apply false
    kotlin("plugin.serialization") version kotlinVer apply false
}

include(
    "access-ingester",
    "common",
    "events",
)
