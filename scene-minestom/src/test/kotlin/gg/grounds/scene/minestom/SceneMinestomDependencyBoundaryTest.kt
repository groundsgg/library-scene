package gg.grounds.scene.minestom

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SceneMinestomDependencyBoundaryTest {
    @Test
    fun `runtime graph excludes host and test implementation families`() {
        val components = System.getProperty("sceneMinestomRuntimeComponents")
        assertTrue(components.isNotBlank(), "Gradle must provide the resolved runtime graph")

        val forbidden =
            listOf(
                "paper",
                "bukkit",
                "resourcepacks",
                "service-maps",
                "plugin-",
                "portal",
                "jackson",
                "testkit",
                "junit",
                "kotest",
            )
        components.split('|').filter(String::isNotBlank).forEach { component ->
            val lowerCaseComponent = component.lowercase()
            forbidden.firstOrNull(lowerCaseComponent::contains)?.let { fragment ->
                throw AssertionError("Forbidden runtime component fragment '$fragment': $component")
            }
        }
    }

    @Test
    fun `production jar contains only Minestom adapter classes`() {
        val jar = System.getProperty("sceneMinestomJar")
        assertTrue(jar.isNotBlank(), "Gradle must provide the built Minestom artifact")

        JarFile(Path.of(jar).toFile()).use { archive ->
            val entries =
                archive.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            assertTrue("gg/grounds/scene/minestom/SceneRuntime.class" in entries)
            assertNoTestContent(entries)
        }
    }

    @Test
    fun `own test classes fixtures and resources are rejected from production jars`() {
        listOf(
                "gg/grounds/scene/minestom/SceneMinestomDependencyBoundaryTest.class",
                "gg/grounds/scene/minestom/SceneMinestomJavaFixtures.class",
                "abi/public-api.txt",
            )
            .forEach { entry ->
                val failure = assertFailsWith<AssertionError> { assertNoTestContent(listOf(entry)) }
                assertTrue(failure.message!!.contains(entry))
            }
    }

    private fun assertNoTestContent(entries: Iterable<String>) {
        entries.firstOrNull(::isTestContent)?.let { entry ->
            throw AssertionError("Test content leaked into production JAR: $entry")
        }
    }

    private fun isTestContent(entry: String): Boolean =
        entry.startsWith("gg/grounds/scene/testkit/") ||
            entry.startsWith("org/junit/") ||
            entry.startsWith("kotlin/test/") ||
            entry.startsWith("abi/") ||
            entry
                .substringAfterLast('/')
                .matches(Regex(".*(?:Test|Tests|Fixture|Fixtures)(?:\\$.*)?\\.class"))
}
