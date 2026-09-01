package gg.grounds.scene.minestom

import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SceneMinestomApiManifestTest {
    @Test
    fun `production jar has the reviewed supported public API only`() {
        val actual = publicApi()
        val expected =
            checkNotNull(javaClass.getResourceAsStream("/abi/public-api.txt")) {
                    "Missing reviewed public API manifest"
                }
                .bufferedReader()
                .useLines { it.filter(String::isNotBlank).toSortedSet() }
        assertEquals(expected, actual)
        assertFalse(
            actual.any { "gg.grounds.scene.minestom.internal." in it },
            "Internal implementation type leaked into public API: ${actual.filter { "gg.grounds.scene.minestom.internal." in it }}",
        )
    }

    private fun publicApi(): Set<String> =
        JarFile(Path.of(System.getProperty("sceneMinestomJar")).toFile()).use { archive ->
            archive
                .entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class").replace('/', '.') }
                .filter { it.startsWith("gg.grounds.scene.minestom.") && ".internal." !in it }
                .mapNotNull { name -> runCatching { Class.forName(name) }.getOrNull() }
                .filter { Modifier.isPublic(it.modifiers) }
                .flatMap { type ->
                    sequenceOf("CLASS ${type.name}") +
                        type.constructors
                            .asSequence()
                            .filter { Modifier.isPublic(it.modifiers) }
                            .map { constructor ->
                                "CTOR ${type.name}(${constructor.parameterTypes.joinToString(",") { it.name }})"
                            } +
                        type.methods
                            .asSequence()
                            .filter { Modifier.isPublic(it.modifiers) && it.declaringClass == type }
                            .map { method ->
                                "METHOD ${type.name}.${method.name}(${method.parameterTypes.joinToString(",") { it.name }}):${method.returnType.name}"
                            }
                }
                .toSortedSet()
        }
}
