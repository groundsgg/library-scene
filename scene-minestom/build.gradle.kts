dependencies {
    api(project(":scene-format"))
    api(platform("gg.grounds:grounds-dependencies:1.0.0"))
    api("net.minestom:minestom")
    implementation("org.slf4j:slf4j-api")

    testImplementation(project(":scene-testkit"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
