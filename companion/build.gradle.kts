import io.papermc.paperweight.userdev.ReobfArtifactConfiguration
import java.util.Properties

plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

val rootProperties = Properties().apply {
    rootDir.parentFile.resolve("gradle.properties").inputStream().use(::load)
}

group = rootProperties.getProperty("group")
version = rootProperties.getProperty("version")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

dependencies {
    paperweight.paperDevBundle("26.2.build.+")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
