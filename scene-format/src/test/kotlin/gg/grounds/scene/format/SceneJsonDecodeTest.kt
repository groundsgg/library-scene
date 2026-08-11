package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SceneJsonDecodeTest {
    @Test fun `decode rejects duplicate keys unknown fields types versions and trailing tokens`() {
        assertFailureCode(validJson().replace("\"id\":\"test:scene\"", "\"id\":\"test:scene\",\"id\":\"test:other\""), "DUPLICATE_FIELD")
        assertFailureCode(validJson().replace("{", "{\"unexpected\":true,"), "UNKNOWN_FIELD")
        assertFailureCode(validJson().replace("\"prop\"", "\"java.lang.Runtime\""), "UNKNOWN_TYPE")
        assertFailureCode(validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "UNSUPPORTED_SCHEMA_VERSION")
        assertFailureCode(validJson() + "{}", "TRAILING_TOKEN")
    }

    @Test fun `decode never returns a partial scene or a Jackson exception`() {
        val result = SceneJson.decode(byteArrayOf(0xC3.toByte(), 0x28))
        assertIs<SceneDecodeResult.Failure>(result)
        assertTrue(result.problems.isNotEmpty())
    }

    @Test fun `decode maps a complete prop scene`() {
        val result = SceneJson.decode(validJson().encodeToByteArray())
        assertIs<SceneDecodeResult.Success>(result)
        assertEquals("test:scene", result.scene.id.value)
        assertIs<Prop>(result.scene.elements.single())
    }

    private fun assertFailureCode(json: String, code: String) {
        val result = SceneJson.decode(json.encodeToByteArray())
        assertIs<SceneDecodeResult.Failure>(result)
        assertEquals(SceneProblemCode.valueOf(code), result.problems.single().code)
    }
}

internal fun validJson() = """{"schemaVersion":1,"id":"test:scene","metadata":{"name":"Scene","description":null,"tags":[]},"catalogs":{"assets":{"id":"test:assets","version":"1"},"actions":{"id":"test:actions","version":"1"}},"groups":[],"elements":[{"type":"prop","id":"prop","group":null,"transform":{"position":{"x":0.0,"y":0.0,"z":0.0},"rotation":{"yaw":0.0,"pitch":0.0,"roll":0.0},"scale":{"x":1.0,"y":1.0,"z":1.0}},"visible":true,"activation":"AUTOMATIC","asset":"test:prop","initialAnimation":null}]}"""
