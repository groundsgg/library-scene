package gg.grounds.scene.format

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SceneRoundTripTest {
    @Test
    fun `decode encode decode preserves the complete scene semantics`() {
        val original =
            assertIs<SceneDecodeResult.Success>(
                    SceneJson.decode(completeJson().encodeToByteArray())
                )
                .scene
        val bytes = assertIs<SceneEncodeResult.Success>(SceneJson.encode(original)).bytes
        val decoded = assertIs<SceneDecodeResult.Success>(SceneJson.decode(bytes)).scene

        assertContentEquals(
            bytes,
            assertIs<SceneEncodeResult.Success>(SceneJson.encode(decoded)).bytes,
        )
    }

    @Test
    fun `public decode encode decode preserves JSON escaped surrogate strings`() {
        val json =
            """
            {"schemaVersion":1,"id":"test:scene","metadata":{"name":"high\uD800 low\uDC00 pair\uD83D\uDE00","description":null,"tags":[]},"catalogs":{"assets":{"id":"test:assets","version":"1"},"actions":{"id":"test:actions","version":"1"}},"groups":[],"elements":[]}
            """
                .trimIndent()

        val first =
            assertIs<SceneDecodeResult.Success>(SceneJson.decode(json.encodeToByteArray())).scene
        val bytes = assertIs<SceneEncodeResult.Success>(SceneJson.encode(first)).bytes
        val second = assertIs<SceneDecodeResult.Success>(SceneJson.decode(bytes)).scene
        val text = bytes.decodeToString()

        assertEquals("high\uD800 low\uDC00 pair😀", first.metadata.name)
        assertEquals(first.metadata.name, second.metadata.name)
        assertTrue("\"name\": \"high\\uD800 low\\uDC00 pair😀\"" in text)
        assertFalse('\uFFFD' in text)
    }

    @Test
    fun `maximum canonical decimal expansion survives exact public roundtrip`() {
        val json = completeJson().replace("\"value\":12.50", "\"value\":1e127")
        val first =
            assertIs<SceneDecodeResult.Success>(SceneJson.decode(json.encodeToByteArray())).scene
        val firstBytes = assertIs<SceneEncodeResult.Success>(SceneJson.encode(first)).bytes
        val second = assertIs<SceneDecodeResult.Success>(SceneJson.decode(firstBytes)).scene
        val secondBytes = assertIs<SceneEncodeResult.Success>(SceneJson.encode(second)).bytes

        assertEquals(0, BigDecimal("1e127").compareTo(decimalArgument(first).value))
        assertEquals(0, BigDecimal("1e127").compareTo(decimalArgument(second).value))
        assertContentEquals(firstBytes, secondBytes)
        assertTrue("\"value\": " + "1" + "0".repeat(127) in firstBytes.decodeToString())
    }

    private fun decimalArgument(scene: SceneDocument): DecimalArgument =
        (((scene.elements.last() as Npc).bindings.single().actions.last() as ApplicationAction)
            .arguments
            .getValue(LocalId("decimal")) as DecimalArgument)
}
