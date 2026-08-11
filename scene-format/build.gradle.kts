dependencies {
    api("net.kyori:adventure-api:4.21.0")
    implementation("net.kyori:adventure-text-serializer-gson:4.21.0")
    implementation("tools.jackson.core:jackson-databind:3.1.5")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.5")
    testImplementation(project(":scene-testkit"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.named("jar"))
    systemProperty(
        "sceneFormatRuntimeComponents",
        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.joinToString(
            "|"
        ) {
            "${it.moduleVersion.id.group}:${it.name}:${it.moduleVersion.id.version}"
        },
    )
    systemProperty(
        "sceneFormatJar",
        tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        val version = requested.version?.split('.')?.map { it.toIntOrNull() ?: 0 }
        val belowFloor =
            version != null &&
                (version[0] < 3 ||
                    version[0] == 3 &&
                        (version.getOrElse(1) { 0 } < 1 ||
                            version.getOrElse(1) { 0 } == 1 && version.getOrElse(2) { 0 } < 4))
        if (requested.group?.startsWith("tools.jackson") == true && belowFloor) {
            throw GradleException("Jackson dependencies must be at least 3.1.4")
        }
    }
}
