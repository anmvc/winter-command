pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "command"

include("core", "paper", "velocity")
project(":core").name = "command-core"
project(":paper").name = "command-paper"
project(":velocity").name = "command-velocity"
