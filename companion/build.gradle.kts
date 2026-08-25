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

tasks.build {
    dependsOn(subprojects.map { "${it.path}:build" })
}
