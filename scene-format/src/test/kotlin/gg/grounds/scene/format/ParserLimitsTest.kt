package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParserLimitsTest {
    @Test fun `decoder enforces byte depth string and numeric limits`() {
        assertCode(ByteArray(16 * 1024 * 1024) { ' '.code.toByte() }, "MALFORMED_JSON")
        assertCode(ByteArray(16 * 1024 * 1024 + 1) { ' '.code.toByte() }, "LIMIT_EXCEEDED")
        assertCode("[".repeat(64) + "]".repeat(64), "MALFORMED_JSON")
        assertCode("[".repeat(65) + "]".repeat(65), "LIMIT_EXCEEDED")
        assertCode("\"" + "a".repeat(65_536) + "\"", "MALFORMED_JSON")
        assertCode("\"" + "a".repeat(65_537) + "\"", "LIMIT_EXCEEDED")
        assertCode("1".repeat(128), "MALFORMED_JSON")
        assertCode("1".repeat(129), "LIMIT_EXCEEDED")
    }

    @Test fun `decoder rejects non finite spellings`() {
        listOf("NaN", "Infinity", "-Infinity").forEach { assertCode(it, "MALFORMED_JSON") }
    }

    private fun assertCode(value: String, expected: String) = assertCode(value.encodeToByteArray(), expected)
    private fun assertCode(bytes: ByteArray, expected: String) {
        val result = SceneJson.decode(bytes)
        assertIs<SceneDecodeResult.Failure>(result)
        assertEquals(SceneProblemCode.valueOf(expected), result.problems.single().code)
    }
}
