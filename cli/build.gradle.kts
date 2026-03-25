plugins {
    application
    id("org.graalvm.buildtools.native")
}

application {
    mainClass.set("io.qorche.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":core"))
    implementation(project(":agent"))
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

tasks.register("generateVersionFile") {
    val outputDir = layout.buildDirectory.dir("generated/version")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("io/qorche/cli")
        dir.mkdirs()
        dir.resolve("version.txt").writeText(project.version.toString())
    }
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/version"))
}

tasks.named("processResources") {
    dependsOn("generateVersionFile")
}

fun sqliteExcludePatterns(): List<String> {
    val os = System.getProperty("os.name", "").lowercase()
    val arch = System.getProperty("os.arch", "").lowercase()

    val allPlatforms = listOf(
        "Linux/x86_64", "Linux/aarch64", "Linux/arm", "Linux/armv6", "Linux/armv7",
        "Linux/android-arm", "Linux/android-aarch64", "Linux/android-x86_64",
        "Linux/ppc64", "Linux/riscv64",
        "Mac/x86_64", "Mac/aarch64",
        "Windows/x86_64", "Windows/x86", "Windows/aarch64",
        "FreeBSD/x86_64"
    )

    val currentPlatform = when {
        os.contains("linux") && arch.contains("aarch64") -> "Linux/aarch64"
        os.contains("linux") -> "Linux/x86_64"
        (os.contains("mac") || os.contains("darwin")) && arch.contains("aarch64") -> "Mac/aarch64"
        os.contains("mac") || os.contains("darwin") -> "Mac/x86_64"
        os.contains("win") && arch.contains("aarch64") -> "Windows/aarch64"
        os.contains("win") -> "Windows/x86_64"
        else -> "Linux/x86_64"
    }

    return allPlatforms.filter { it != currentPlatform }.map { platform ->
        "-H:ExcludeResources=org/sqlite/native/${platform.replace("/", "/")}.*"
    }
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("io.qorche.cli.MainKt")
            imageName.set("qorche")
            buildArgs.addAll(
                listOf(
                    "--no-fallback",
                    "-Ob",
                    "--gc=serial",
                    "-H:+UnlockExperimentalVMOptions",
                    "-H:+ReportExceptionStackTraces"
                ) + sqliteExcludePatterns()
            )
        }
    }
    metadataRepository {
        enabled.set(true)
    }
}
