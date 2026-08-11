package gg.grounds.scene.format

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyBoundaryTest {
    @Test
    fun `format runtime classpath contains no platform testkit or test framework dependency`() {
        val components = System.getProperty("sceneFormatRuntimeComponents").split("|")
        val forbidden =
            listOf(
                "org.bukkit:",
                "io.papermc:",
                "net.minestom:",
                "io.quarkus:",
                "gg.grounds:scene-testkit:",
                "org.junit",
                "kotlin-test",
                "resource-pack",
                "service-maps",
            )

        forbidden.forEach { marker ->
            assertFalse(
                components.any { it.lowercase().contains(marker) },
                "format runtime contains $marker: $components",
            )
        }
        assertTrue(components.any { it.contains("adventure-api") })
        assertTrue(components.any { it.contains("jackson") })
    }

    @Test
    fun `format production jar contains no platform testkit or test implementation classes`() {
        val jar = Path.of(System.getProperty("sceneFormatJar"))
        JarFile(jar.toFile()).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toList()
            listOf(
                    "gg/grounds/scene/testkit/",
                    "org/junit/",
                    "kotlin/test/",
                    "org/bukkit/",
                    "io/papermc/",
                    "net/minestom/",
                )
                .forEach { forbidden ->
                    assertFalse(
                        entries.any { it.startsWith(forbidden) },
                        "$jar contains $forbidden",
                    )
                }
            assertFalse(entries.any { it.contains("SyntheticPublicSurfaceFixture") })
            assertTrue(entries.any { it == "gg/grounds/scene/format/SceneJson.class" })
        }
    }
}
