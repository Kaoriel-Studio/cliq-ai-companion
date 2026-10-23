import org.jetbrains.intellij.platform.gradle.TestFrameworkType

val pluginGroup: String by project
val pluginVersion: String by project
val platformVersion: String by project

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = pluginGroup
version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        create("IC", platformVersion)
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        instrumentationTools()
        testFramework(TestFrameworkType.Platform)
    }

    configurations.all {
        exclude(group = "org.slf4j", module = "slf4j-api")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.modelcontextprotocol:kotlin-sdk:0.7.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("243")
            untilBuild.set("252.*")
        }
        changeNotes.set("""
            This update adds a multi-file review queue, AI-drafted commit messages, prompt history, and smarter templates.<br>
            <br>
            Highlights:<br>
            <ul>
              <li>New "Pending Changes" panel reviews every open diff from an agent session at once, with Accept All / Reject All</li>
              <li>"Cliq: Generate Commit Message" builds a diff of your changes and drafts a ready-to-send prompt for your agent</li>
              <li>Chat input now keeps a per-project prompt history you can reinsert or clear</li>
              <li>Prompt templates support {{activeFile}}, {{selection}}, and {{clipboard}} placeholders</li>
              <li>"Cliq: Collect Pull/Merge Request Context" gathers diffs, files, tests, CI status and discussions of a GitHub pull request or GitLab merge request into token-budgeted chunks and attaches them to the Cliq context with a review prompt</li>
            </ul>
        """.trimIndent())
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }

    prepareJarSearchableOptions {
        enabled = false
    }

    jarSearchableOptions {
        enabled = false
    }

    val buildPluginTarGz = register<Tar>("buildPluginTarGz") {
        group = "distribution"
        description = "Упаковывает плагин в tar.gz архив."

        from(zipTree(buildPlugin.flatMap { it.archiveFile }))

        archiveExtension.set("tar.gz")
        compression = Compression.GZIP

        destinationDirectory.set(buildPlugin.flatMap { it.destinationDirectory })
    }

    build {
        dependsOn(buildPluginTarGz)
    }

    signPlugin {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }

    test {
        useJUnit()
    }
}