dependencies {
    api(project(":scene-format")) {
        exclude(group = "tools.jackson.core")
        exclude(group = "tools.jackson.module")
    }
    api(platform("gg.grounds:grounds-dependencies:1.0.0"))
    api("net.minestom:minestom")
    implementation("org.slf4j:slf4j-api")

    testImplementation(project(":scene-testkit"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.named<Jar>("jar"))
    classpath = classpath.minus(sourceSets.main.get().output).plus(files(tasks.named<Jar>("jar")))
    systemProperty(
        "sceneMinestomRuntimeComponents",
        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.joinToString(
            "|"
        ) {
            "${it.moduleVersion.id.group}:${it.name}:${it.moduleVersion.id.version}"
        },
    )
    systemProperty(
        "sceneMinestomJar",
        tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
}
