plugins {
    application
    id("org.graalvm.buildtools.native")
}

application {
    mainClass.set("io.qorche.cli.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":agent"))
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("io.qorche.cli.MainKt")
            imageName.set("qorche")
            buildArgs.add("--no-fallback")
        }
    }
}
