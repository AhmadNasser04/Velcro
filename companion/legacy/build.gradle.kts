base.archivesName = "velcro-companion-legacy"

dependencies {
    compileOnly(libs.paper.api.legacy)
    implementation(project(":common"))
}

tasks.compileJava {
    options.release = 21
}

tasks.jar {
    val commonJar = project(":common").tasks.named<Jar>("jar")
    from(commonJar.map { zipTree(it.archiveFile) }) {
        exclude("META-INF/**")
    }
}
