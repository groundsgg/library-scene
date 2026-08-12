package gg.grounds.scene.format

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test

class JvmPublicSurfaceJavaTest {
    @Test
    fun `production jar has the exact approved class and ABI manifest`() {
        val classFiles = productionClassFiles()
        val expectedEntries = manifest("/abi/scene-format-classes.txt")
        val expectedPublicApi = manifest("/abi/kotlin-public-classes.txt")
        val expectedAbi = manifest("/abi/public-abi.txt")

        AbiBoundary.assertNoReachableImplementationSurface(classFiles, expectedPublicApi)
        AbiBoundary.assertExactKotlinPublicClasses(classFiles, expectedPublicApi)
        AbiBoundary.assertExact(classFiles, expectedEntries, expectedPublicApi, expectedAbi)
    }

    private fun manifest(name: String): Set<String> =
        checkNotNull(javaClass.getResourceAsStream(name)) { "Missing literal manifest $name" }
            .bufferedReader()
            .useLines { lines -> lines.filter(String::isNotBlank).toCollection(linkedSetOf()) }

    private fun productionClassFiles(): Map<String, ByteArray> {
        val jar = Path.of(System.getProperty("sceneFormatJar"))
        return JarFile(jar.toFile()).use { archive ->
            archive
                .entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .associate { entry -> entry.name to archive.getInputStream(entry).readAllBytes() }
        }
    }
}
