package gg.grounds.scene.minestom

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        assertNoInternalSignatures(actual)
    }

    @Test
    fun `public fields are recorded in the API manifest`() {
        val records = recordsFor(SceneRuntimeProblemCode::class.java)
        assertTrue(
            "FIELD gg.grounds.scene.minestom.SceneRuntimeProblemCode.INVALID_SCENE:gg.grounds.scene.minestom.SceneRuntimeProblemCode" in
                records
        )
    }

    @Test
    fun `generic public signatures cannot expose implementation types`() {
        val records = recordsFor(GenericInternalSignatureFixture::class.java)
        assertFailsWith<AssertionError> { assertNoInternalSignatures(records.toList()) }
    }

    @Test
    fun `public class loading failures identify their owner and cause`() {
        val failure =
            assertFailsWith<AssertionError> {
                loadPublicClass(
                    "gg.grounds.scene.minestom.MissingType",
                    ClassLoader.getSystemClassLoader(),
                )
            }
        assertTrue(failure.message!!.contains("gg.grounds.scene.minestom.MissingType"))
        assertTrue(failure.cause is ClassNotFoundException)
    }

    private fun publicApi(): Set<String> =
        JarFile(Path.of(System.getProperty("sceneMinestomJar")).toFile()).use { archive ->
            archive
                .entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class").replace('/', '.') }
                .filter { it.startsWith("gg.grounds.scene.minestom.") && ".internal." !in it }
                .map { loadPublicClass(it, javaClass.classLoader) }
                .filter { Modifier.isPublic(it.modifiers) }
                .flatMap(::recordsFor)
                .toSortedSet()
        }

    private fun loadPublicClass(name: String, classLoader: ClassLoader): Class<*> =
        try {
            Class.forName(name, false, classLoader)
        } catch (cause: Throwable) {
            throw AssertionError("Unable to load public API class: $name", cause)
        }

    private fun recordsFor(type: Class<*>): Sequence<String> =
        sequenceOf(
            "CLASS ${type.name}:${type.genericSuperclass?.typeName ?: "-"} interfaces=${type.genericInterfaces.types()}${bounds(type.typeParameters)}"
        ) +
            type.fields
                .asSequence()
                .filter { Modifier.isPublic(it.modifiers) && it.declaringClass == type }
                .map(::fieldRecord) +
            type.constructors
                .asSequence()
                .filter { Modifier.isPublic(it.modifiers) }
                .map(::constructorRecord) +
            type.methods
                .asSequence()
                .filter { Modifier.isPublic(it.modifiers) && it.declaringClass == type }
                .map(::methodRecord)

    private fun fieldRecord(field: Field): String =
        "FIELD ${field.declaringClass.name}.${field.name}:${field.genericType.typeName}"

    private fun constructorRecord(constructor: Constructor<*>): String =
        "CTOR ${constructor.declaringClass.name}(${constructor.genericParameterTypes.types()}) throws=${constructor.genericExceptionTypes.types()}${bounds(constructor.typeParameters)}"

    private fun methodRecord(method: Method): String =
        "METHOD ${method.declaringClass.name}.${method.name}(${method.genericParameterTypes.types()}):${method.genericReturnType.typeName} throws=${method.genericExceptionTypes.types()}${bounds(method.typeParameters)}"

    private fun Array<Type>.types(): String = joinToString(",") { it.typeName }

    private fun bounds(parameters: Array<out java.lang.reflect.TypeVariable<*>>): String =
        parameters
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " bounds=", separator = ",") {
                "${it.name}:${it.bounds.types()}"
            } ?: ""

    private fun assertNoInternalSignatures(records: Iterable<String>) {
        records
            .firstOrNull { "gg.grounds.scene.minestom.internal." in it }
            ?.let { record ->
                throw AssertionError("Internal implementation type leaked into public API: $record")
            }
    }
}
