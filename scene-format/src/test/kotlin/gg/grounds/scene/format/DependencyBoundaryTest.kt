package gg.grounds.scene.format

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DependencyBoundaryTest {
    @Test
    fun `format runtime contains only the exact approved component identities`() {
        val components =
            System.getProperty("sceneFormatRuntimeComponents")
                .split("|")
                .filter(String::isNotBlank)
                .map(RuntimeComponent::parse)
        DependencyBoundary.assertNoForbiddenComponents(components)
        assertTrue(components.any { it.identity == "net.kyori:adventure-api" })
        assertTrue(components.any { it.identity == "tools.jackson.core:jackson-databind" })
    }

    @Test
    fun `every forbidden component identity is mutation-sensitive`() {
        listOf(
                "gg.grounds:service-maps",
                "gg.grounds:resource-pack-api",
                "gg.grounds:resource-pack-builder",
                "gg.grounds:resource-pack-testkit",
                "gg.grounds:scene-testkit",
                "io.papermc.paper:paper-api",
                "io.papermc.paper:paper-server",
                "org.bukkit:bukkit",
                "org.bukkit:craftbukkit",
                "net.minestom:minestom",
                "net.minestom:minestom-snapshots",
                "io.quarkus:quarkus-core",
                "io.quarkus:quarkus-arc",
                "org.junit.jupiter:junit-jupiter",
                "org.junit.jupiter:junit-jupiter-api",
                "org.junit.jupiter:junit-jupiter-engine",
                "org.junit.jupiter:junit-jupiter-params",
                "org.junit.platform:junit-platform-commons",
                "org.junit.platform:junit-platform-engine",
                "org.junit.platform:junit-platform-launcher",
                "org.junit.vintage:junit-vintage-engine",
                "junit:junit",
                "org.jetbrains.kotlin:kotlin-test",
                "org.jetbrains.kotlin:kotlin-test-annotations-common",
                "org.jetbrains.kotlin:kotlin-test-common",
                "org.jetbrains.kotlin:kotlin-test-junit",
                "org.jetbrains.kotlin:kotlin-test-junit5",
                "org.jetbrains.kotlin:kotlin-test-testng",
            )
            .forEach { identity ->
                val failure =
                    assertFailsWith<AssertionError> {
                        DependencyBoundary.assertNoForbiddenComponents(
                            listOf(RuntimeComponent.parse("$identity:decoy"))
                        )
                    }
                assertEquals("Forbidden runtime component: $identity:decoy", failure.message)
            }
    }

    @Test
    fun `format production jar contains no forbidden family classes`() {
        val jar = Path.of(System.getProperty("sceneFormatJar"))
        JarFile(jar.toFile()).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toSet()
            DependencyBoundary.assertNoForbiddenJarEntries(entries)
            assertTrue("gg/grounds/scene/format/SceneJson.class" in entries)
        }
    }

    @Test
    fun `every forbidden jar package prefix is mutation-sensitive`() {
        mapOf(
                "gg/grounds/servicemaps/Decoy.class" to "gg/grounds/servicemaps/",
                "gg/grounds/maps/Decoy.class" to "gg/grounds/maps/",
                "gg/grounds/resourcepack/api/Decoy.class" to "gg/grounds/resourcepack/api/",
                "gg/grounds/resourcepack/builder/Decoy.class" to "gg/grounds/resourcepack/builder/",
                "gg/grounds/resourcepack/testkit/Decoy.class" to "gg/grounds/resourcepack/testkit/",
                "gg/grounds/scene/testkit/Decoy.class" to "gg/grounds/scene/testkit/",
                "io/papermc/paper/Decoy.class" to "io/papermc/",
                "org/bukkit/Decoy.class" to "org/bukkit/",
                "net/minestom/server/Decoy.class" to "net/minestom/",
                "io/quarkus/runtime/Decoy.class" to "io/quarkus/",
                "org/junit/jupiter/api/Decoy.class" to "org/junit/",
                "kotlin/test/Decoy.class" to "kotlin/test/",
            )
            .forEach { (entry, prefix) ->
                val failure =
                    assertFailsWith<AssertionError> {
                        DependencyBoundary.assertNoForbiddenJarEntries(setOf(entry))
                    }
                assertEquals("Forbidden JAR entry: $entry (prefix $prefix)", failure.message)
            }
    }
}
