@file:OptIn(ExperimentalWasmDsl::class)

import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.mokkery)
    alias(libs.plugins.dokka)
}

dokka {
    dokkaSourceSets.configureEach {
        includes.from("module.md")
    }
}

group = "io.github.notoriouscorgi"
version = System.getenv("RELEASE_VERSION")?.removePrefix("v") ?: "0.0.1"

kotlin {
    jvm()
    androidLibrary {
        namespace = "io.github.notoriouscorgi.cacheonhand.compose"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

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
            api(project(":cacheonhand-attendants"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.atomicfu)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.test)
        }

        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name = "Cache On Hand - Compose"
        description =
            "Compose Multiplatform convenience wrappers for Cache On Hand with SnapshotStateList optimizations."
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
