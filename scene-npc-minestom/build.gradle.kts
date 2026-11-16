dependencies {
    api(project(":scene-minestom"))
    api(
        "gg.grounds:npc-minestom:${providers.gradleProperty("npcVersion").orElse("0.3.0-api-review-SNAPSHOT").get()}"
    )
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
