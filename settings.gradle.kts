rootProject.name = "budget"

pluginManagement {

}

plugins {
    val kotlinVer = "2.4.0"
    kotlin("jvm") version kotlinVer apply false
    kotlin("plugin.serialization") version kotlinVer apply false
}

include(
    "access-ingester",
    "common",
    "db-sqlite",
    "desktop",
    "events",
)
