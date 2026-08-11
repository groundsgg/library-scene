package gg.grounds.scene.format

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyBoundaryTest {
    @Test
    fun `format runtime classpath contains no platform testkit or test framework dependency`() {
        val classpath =
            System.getProperty("sceneFormatRuntimeClasspath")
                .split(System.getProperty("path.separator"))
        val forbidden =
            listOf(
                "bukkit",
                "paper",
                "minestom",
                "quarkus",
                "scene-testkit",
                "junit",
                "kotlin-test",
            )

        forbidden.forEach { marker ->
            assertFalse(
                classpath.any { it.lowercase().contains(marker) },
                "format runtime contains $marker: $classpath",
            )
        }
        assertTrue(classpath.any { it.contains("adventure-api") })
        assertTrue(classpath.any { it.contains("jackson") })
    }

    @Test
    fun `format production jar contains no platform testkit or test implementation classes`() {
        val jar =
            Files.list(Path.of("build/libs")).use { paths ->
                paths
                    .filter { it.extension == "jar" && !it.name.endsWith("-sources.jar") }
                    .findFirst()
                    .orElseThrow()
            }
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
