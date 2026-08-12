package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParserLimitsTest {
    @Test
    fun `decoder accepts exactly 16 MiB and rejects one byte over`() {
        val document = validJson().encodeToByteArray()
        val exact = ByteArray(16 * 1024 * 1024) { ' '.code.toByte() }
        document.copyInto(exact)
        assertIs<SceneDecodeResult.Success>(SceneJson.decode(exact))
        assertCode(exact + ' '.code.toByte(), SceneProblemCode.LIMIT_EXCEEDED)
    }

    @Test
    fun `decoder accepts depth 64 and rejects depth 65 in an otherwise valid scene`() {
        assertIs<SceneDecodeResult.Success>(
            SceneJson.decode(sceneWithMessage(nestedComponent(29)).encodeToByteArray())
        )
        assertCode(
            sceneWithMessage(nestedComponent(29, terminalExtra = true)),
            SceneProblemCode.LIMIT_EXCEEDED,
        )
    }

    @Test
    fun `decoder accepts 65536 character strings and rejects 65537`() {
        assertIs<SceneDecodeResult.Success>(
            SceneJson.decode(sceneWithDescription("a".repeat(65_536)).encodeToByteArray())
        )
        assertCode(sceneWithDescription("a".repeat(65_537)), SceneProblemCode.LIMIT_EXCEEDED)
    }

    @Test
    fun `decoder accepts 128 character number tokens and rejects 129`() {
        assertIs<SceneDecodeResult.Success>(
            SceneJson.decode(sceneWithPositionX("1".repeat(128)).encodeToByteArray())
        )
        assertCode(sceneWithPositionX("1".repeat(129)), SceneProblemCode.LIMIT_EXCEEDED)
    }

    @Test
    fun `decoder rejects non finite spellings in an otherwise valid scene`() {
        listOf("NaN", "Infinity", "-Infinity").forEach {
            assertCode(sceneWithPositionX(it), SceneProblemCode.MALFORMED_JSON)
        }
    }

    private fun sceneWithDescription(description: String) =
        validJson().replace("\"description\":null", "\"description\":\"$description\"")

    private fun sceneWithPositionX(number: String) =
        validJson().replaceFirst("\"x\":0.0", "\"x\":$number")

    private fun sceneWithMessage(component: String) =
        npcScene(action = """{"type":"send_message","message":$component}""")

    private fun nestedComponent(objectCount: Int, terminalExtra: Boolean = false): String {
        var component =
            if (terminalExtra) """{"text":"leaf","extra":["end"]}""" else """{"text":"leaf"}"""
        repeat(objectCount - 1) { component = """{"text":"node","extra":[$component]}""" }
        return component
    }

    private fun assertCode(value: String, expected: SceneProblemCode) =
        assertCode(value.encodeToByteArray(), expected)

    private fun assertCode(bytes: ByteArray, expected: SceneProblemCode) {
        val result = SceneJson.decode(bytes)
        assertIs<SceneDecodeResult.Failure>(result)
        assertEquals(expected, result.problems.single().code)
    }
}
