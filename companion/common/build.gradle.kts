dependencies {
    compileOnly(libs.paper.api.legacy)
}

tasks.compileJava {
    // Legacy servers run Java 21; common is bundled into both jars.
    options.release = 21
}
