pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cache-on-hand"
include(":cacheonhand")
include(":cacheonhand-attendants")
include(":cacheonhand-compose")
