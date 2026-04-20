@file:OptIn(ExperimentalWasmDsl::class)

import com.android.build.api.dsl.androidLibrary
import dev.mokkery.gradle.ApplicationRule
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.mokkery)
    alias(libs.plugins.allopen)
    alias(libs.plugins.dokka)
}

dokka {
    dokkaSourceSets.configureEach {
        includes.from("module.md")
    }
}

group = "io.github.notoriouscorgi"
version = System.getenv("RELEASE_VERSION")?.removePrefix("v") ?: "0.0.1-SNAPSHOT"

kotlin {
    jvm()
    androidLibrary {
        namespace = "io.github.notoriouscorgi.cacheonhand.attendants"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        withHostTestBuilder {
        }

        withDeviceTestBuilder {
            sourceSetTreeName = "instrumentedTest"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compilations.configureEach {
            compilerOptions.configure {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(project(":cacheonhand"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        val androidDeviceTest by getting {
            dependencies {
                implementation("androidx.compose.ui:ui-test-junit4-android:1.8.2")
                implementation("androidx.compose.ui:ui-test-manifest:1.8.2")
            }
        }
    }
}

mokkery {
    rule.set(ApplicationRule.All)
}

fun isTestingTask(name: String) = name.endsWith("Test")

val isTesting =
    gradle
        .startParameter
        .taskNames
        .any(::isTestingTask)

if (isTesting) {
    allOpen {
        annotation("io.github.notoriouscorgi.OpenForMokkery")
    }
}

mavenPublishing {
    val isSnapshot = version.toString().endsWith("SNAPSHOT")
    val host =
        if (isSnapshot) {
            com.vanniktech.maven.publish.SonatypeHost.S01
        } else {
            com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL
        }
    publishToMavenCentral(host)
    signAllPublications()

    pom {
        name = "Cache On Hand - Attendants"
        description = "Reactive caching operations (queries, mutations, flows, infinite queries) for Kotlin Multiplatform."
        url = "https://github.com/notoriouscorgi/CacheOnHand"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "notoriouscorgi"
                name = "notoriouscorgi"
                url = "https://github.com/notoriouscorgi"
            }
        }
        scm {
            url = "https://github.com/notoriouscorgi/CacheOnHand"
            connection = "scm:git:git://github.com/notoriouscorgi/CacheOnHand.git"
            developerConnection = "scm:git:ssh://git@github.com/notoriouscorgi/CacheOnHand.git"
        }
    }
}
