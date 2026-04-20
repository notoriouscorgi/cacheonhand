plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.dokka)
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(layout.projectDirectory.dir("docs"))
        includes.from("module.md")
    }
}

dependencies {
    dokka(project(":cacheonhand"))
    dokka(project(":cacheonhand-attendants"))
    dokka(project(":cacheonhand-compose"))
}

val generateModuleDocs by tasks.registering {
    description = "Generates module.md files from READMEs for Dokka"

    val modules = mapOf(
        "cache-on-hand" to layout.projectDirectory.file("README.md"),
        "cacheonhand" to layout.projectDirectory.file("cacheonhand/README.md"),
        "cacheonhand-attendants" to layout.projectDirectory.file("cacheonhand-attendants/README.md"),
        "cacheonhand-compose" to layout.projectDirectory.file("cacheonhand-compose/README.md"),
    )

    val outputFiles = mapOf(
        "cache-on-hand" to layout.projectDirectory.file("module.md"),
        "cacheonhand" to layout.projectDirectory.file("cacheonhand/module.md"),
        "cacheonhand-attendants" to layout.projectDirectory.file("cacheonhand-attendants/module.md"),
        "cacheonhand-compose" to layout.projectDirectory.file("cacheonhand-compose/module.md"),
    )

    inputs.files(modules.values.map { it.asFile })
    outputs.files(outputFiles.values.map { it.asFile })

    doLast {
        modules.forEach { (moduleName, readmeFile) ->
            val readme = readmeFile.asFile.readText()
            // Strip the first markdown heading — Dokka uses "# Module <name>" as the title
            val contentWithoutTitle = readme.replaceFirst(Regex("^#\\s+.+\\n*"), "")
            val moduleDoc = "# Module $moduleName\n\n$contentWithoutTitle"
            outputFiles[moduleName]!!.asFile.writeText(moduleDoc)
        }
    }
}

tasks.named("dokkaGenerate") {
    dependsOn(generateModuleDocs)
}

subprojects {
    tasks.configureEach {
        if (name.startsWith("dokka")) {
            dependsOn(generateModuleDocs)
        }
    }
}
