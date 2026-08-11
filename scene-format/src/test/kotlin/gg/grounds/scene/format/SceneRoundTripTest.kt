package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

class SceneRoundTripTest {
    @Test
    fun `decode encode decode preserves the complete scene semantics`() {
        val original = assertIs<SceneDecodeResult.Success>(SceneJson.decode(completeJson().encodeToByteArray())).scene
        val bytes = assertIs<SceneEncodeResult.Success>(SceneJson.encode(original)).bytes
        val decoded = assertIs<SceneDecodeResult.Success>(SceneJson.decode(bytes)).scene

        assertContentEquals(bytes, assertIs<SceneEncodeResult.Success>(SceneJson.encode(decoded)).bytes)
    }
}
