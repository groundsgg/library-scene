dependencies {
    api(project(":scene-minestom"))
    api("gg.grounds:npc-minestom:${providers.gradleProperty("npcVersion").orElse("0.2.0").get()}")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
