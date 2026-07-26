plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-library")
}

kotlin {
    jvmToolchain(libs.versions.toolchain.get().toInt())
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(libs.versions.jvmTarget.get().toInt())
}

tasks.withType<Test>().configureEach {
    // Opt-in corpus harness (see CorpusHarness). Absent by default, so the
    // harness skips and no personal data is ever needed to build or test.
    System.getProperty("spendlens.corpus")?.let { systemProperty("spendlens.corpus", it) }
    testLogging { showStandardStreams = true }
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
}
