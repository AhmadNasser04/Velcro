pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            // Oldest Paper with the player connection API (AsyncPlayerConnectionConfigureEvent).
            library("paper-api-legacy", "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
        }
    }
}

rootProject.name = "velcro-companion"

include("common", "modern", "legacy")
