rootProject.name = "GeoShare Server"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("ktorLibs").from("io.ktor:ktor-version-catalog:3.4.0") // Use 3.4.0, because rate limiting unit tests fail with 3.5.1
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

includeBuild("libs/keyattestation") {
    dependencySubstitution {
        substitute(module("com.android:keyattestation")).using(project(":"))
    }
}
