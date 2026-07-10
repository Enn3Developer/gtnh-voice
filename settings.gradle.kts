
pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.20")
}

// Shared MC-free protocol module (crypto + wire format), single source of truth for the mod and the
// :exploit harness. Shaded into the mod jar via shadowImplementation.
include("protocol")

// Throwaway spike: a raw Netty login/FML-handshake harness (see :security). Not part of the mod build.
include("security")
