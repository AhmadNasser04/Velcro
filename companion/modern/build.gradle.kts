import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

base.archivesName = "velcro-companion-modern"

paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

dependencies {
    paperweight.paperDevBundle("26.2.build.+")
    implementation(project(":common"))
}

tasks.jar {
    val commonJar = project(":common").tasks.named<Jar>("jar")
    from(commonJar.map { zipTree(it.archiveFile) }) {
        exclude("META-INF/**")
    }
}
