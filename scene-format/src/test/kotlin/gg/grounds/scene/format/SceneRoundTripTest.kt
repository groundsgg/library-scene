package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

class SceneRoundTripTest {
    @Test
    fun `decode encode decode preserves the complete scene semantics`() {
        val original = assertIs<SceneDecodeResult.Success>(SceneJson.decode(completeJson().encodeToByteArray())).scene
        val bytes = assertIs<SceneEncodeResult.Success>(SceneJson.encode(original)).bytes
        val decoded = assertIs<SceneDecodeResult.Success>(SceneJson.decode(bytes)).scene

        assertContentEquals(bytes, assertIs<SceneEncodeResult.Success>(SceneJson.encode(decoded)).bytes)
    }

    @Test
    fun `public decode encode decode preserves JSON escaped surrogate strings`() {
        val json = """
            {"schemaVersion":1,"id":"test:scene","metadata":{"name":"high\uD800 low\uDC00 pair\uD83D\uDE00","description":null,"tags":[]},"catalogs":{"assets":{"id":"test:assets","version":"1"},"actions":{"id":"test:actions","version":"1"}},"groups":[],"elements":[{"type":"prop","id":"prop","group":null,"transform":{"position":{"x":0,"y":0,"z":0},"rotation":{"yaw":0,"pitch":0,"roll":0},"scale":{"x":1,"y":1,"z":1}},"visible":true,"activation":"AUTOMATIC","asset":"test:prop","initialAnimation":null}]}
        """.trimIndent()

        val first = assertIs<SceneDecodeResult.Success>(SceneJson.decode(json.encodeToByteArray())).scene
        val bytes = assertIs<SceneEncodeResult.Success>(SceneJson.encode(first)).bytes
        val second = assertIs<SceneDecodeResult.Success>(SceneJson.decode(bytes)).scene
        val text = bytes.decodeToString()

        assertEquals("high\uD800 low\uDC00 pair😀", first.metadata.name)
        assertEquals(first.metadata.name, second.metadata.name)
        assertTrue("\"name\": \"high\\uD800 low\\uDC00 pair😀\"" in text)
        assertFalse('\uFFFD' in text)
    }
}
