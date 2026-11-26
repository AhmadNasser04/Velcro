import java.util.Properties

plugins {
    base
}

val rootProperties = Properties().apply {
    rootDir.parentFile.resolve("gradle.properties").inputStream().use(::load)
}

subprojects {
    apply(plugin = "java-library")

    group = rootProperties.getProperty("group")
    version = rootProperties.getProperty("version")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    tasks.withType<ProcessResources>().configureEach {
        inputs.property("version", project.version)
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
}

// The two plugin jars are published with the same per-commit version as the proxy jar,
// e.g. com.velocitypowered:velcro-companion-legacy:4.3.2-1e12ff35.
val velcroVersion = providers.exec { commandLine("git", "rev-parse", "--short=8", "HEAD") }
    .standardOutput.asText
    .map { "${rootProperties.getProperty("version").removeSuffix("-SNAPSHOT")}-${it.trim()}" }

configure(listOf(project(":modern"), project(":legacy"))) {
    apply(plugin = "maven-publish")

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://repo.striveservices.org/nexus-public") {
                name = "strive"
                credentials {
                    username = "Panda"
                    password = System.getenv("PANDA_TOKEN")
                }
            }
        }
        publications {
            create<MavenPublication>("velcro") {
                artifactId = "velcro-companion-${project.name}"
                version = velcroVersion.get()
                artifact(tasks.named("jar"))
            }
        }
    }
}

tasks.build {
    dependsOn(subprojects.map { "${it.path}:build" })
}
