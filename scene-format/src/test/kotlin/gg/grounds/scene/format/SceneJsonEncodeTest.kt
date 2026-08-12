package gg.grounds.scene.format

import gg.grounds.scene.testkit.SceneFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kyori.adventure.text.Component

class SceneJsonEncodeTest {
    @Test
    fun `canonical bytes use the hand derived wire layout and one newline`() {
        val scene =
            assertIs<SceneDecodeResult.Success>(SceneJson.decode(validJson().encodeToByteArray()))
                .scene

        val bytes = assertIs<SceneEncodeResult.Success>(SceneJson.encode(scene)).bytes
        val text = bytes.decodeToString()

        assertEquals(expectedPropScene(), text)
        assertTrue(text.endsWith("\n"))
        assertFalse(text.endsWith("\n\n"))
        assertFalse("-0" in text)
        assertFalse("E" in text || "e+" in text)
    }

    @Test
    fun `encode returns all intrinsic problems without bytes`() {
        val valid =
            assertIs<SceneDecodeResult.Success>(SceneJson.decode(validJson().encodeToByteArray()))
                .scene
        val invalid =
            SceneDocument(
                valid.schemaVersion,
                valid.id,
                valid.metadata,
                valid.catalogs,
                valid.groups,
                valid.elements + valid.elements,
            )

        val result = assertIs<SceneEncodeResult.Failure>(SceneJson.encode(invalid))

        assertEquals(listOf(SceneProblemCode.DUPLICATE_ELEMENT_ID), result.problems.map { it.code })
    }

    @Test
    fun `canonical strings preserve isolated surrogates and valid supplementary pairs`() {
        val base =
            assertIs<SceneDecodeResult.Success>(SceneJson.decode(validJson().encodeToByteArray()))
                .scene
        val source = "high\uD800 low\uDC00 pair\uD83D\uDE00"
        val scene =
            SceneDocument(
                base.schemaVersion,
                base.id,
                SceneMetadata(source, base.metadata.description, base.metadata.tags),
                base.catalogs,
                base.groups,
                base.elements,
            )

        val bytes = assertIs<SceneEncodeResult.Success>(SceneJson.encode(scene)).bytes
        val text = bytes.decodeToString()
        val decoded = assertIs<SceneDecodeResult.Success>(SceneJson.decode(bytes)).scene

        assertTrue("\"name\": \"high\\uD800 low\\uDC00 pair😀\"" in text)
        assertFalse('\uFFFD' in text)
        assertEquals(source, decoded.metadata.name)
    }

    @Test
    fun `encode rejects canonical strings beyond the decoder limit`() {
        val scene = withMetadata(SceneFixtures.minimal(), description = "a".repeat(65_537))

        assertEncodingFailure(scene)
    }

    @Test
    fun `encode rejects canonical bytes beyond the decoder limit`() {
        val displayName = "a".repeat(4_096)
        val groups = (0 until 4_096).map { SceneGroup(LocalId("g$it"), displayName) }
        val base = SceneFixtures.minimal()
        val scene =
            SceneDocument(
                base.schemaVersion,
                base.id,
                base.metadata,
                base.catalogs,
                groups,
                base.elements,
            )

        assertEncodingFailure(scene)
    }

    @Test
    fun `encode rejects component nesting beyond the decoder limit`() {
        var label: Component = Component.text("leaf")
        repeat(64) { label = Component.text("node").append(label) }
        val base = SceneFixtures.complete()
        val elements =
            base.elements.map { element ->
                if (element !is Npc || element.id.value != "tracked") element
                else
                    Npc(
                        element.id,
                        element.group,
                        element.transform,
                        element.visible,
                        element.activation,
                        element.body,
                        label,
                        element.labelOffset,
                        element.look,
                        element.initialAnimation,
                        element.interactionBounds,
                        element.proximity,
                        element.bindings,
                    )
            }
        val scene =
            SceneDocument(
                base.schemaVersion,
                base.id,
                base.metadata,
                base.catalogs,
                base.groups,
                elements,
            )

        assertEncodingFailure(scene)
    }

    @Test
    fun `encode accepts and round trips the maximum decoder string length`() {
        val scene = withMetadata(SceneFixtures.minimal(), description = "a".repeat(65_536))

        val encoded = assertIs<SceneEncodeResult.Success>(SceneJson.encode(scene))
        val decoded = assertIs<SceneDecodeResult.Success>(SceneJson.decode(encoded.bytes))

        assertEquals(scene, decoded.scene)
    }

    private fun withMetadata(scene: SceneDocument, description: String) =
        SceneDocument(
            scene.schemaVersion,
            scene.id,
            SceneMetadata(scene.metadata.name, description, scene.metadata.tags),
            scene.catalogs,
            scene.groups,
            scene.elements,
        )

    private fun assertEncodingFailure(scene: SceneDocument) {
        val failure = assertIs<SceneEncodeResult.Failure>(SceneJson.encode(scene))
        assertEquals(listOf(SceneProblemCode.ENCODING_FAILURE), failure.problems.map { it.code })
        assertEquals(listOf("/"), failure.problems.map { it.path })
    }
}

internal fun expectedPropScene() =
    """
    {
      "schemaVersion": 1,
      "id": "test:scene",
      "metadata": {
        "name": "Scene",
        "description": null,
        "tags": []
      },
      "catalogs": {
        "assets": {
          "id": "test:assets",
          "version": "1"
        },
        "actions": {
          "id": "test:actions",
          "version": "1"
        }
      },
      "groups": [],
      "elements": [
        {
          "type": "prop",
          "id": "prop",
          "group": null,
          "transform": {
            "position": {
              "x": 0,
              "y": 0,
              "z": 0
            },
            "rotation": {
              "yaw": 0,
              "pitch": 0,
              "roll": 0
            },
            "scale": {
              "x": 1,
              "y": 1,
              "z": 1
            }
          },
          "visible": true,
          "activation": "AUTOMATIC",
          "asset": "test:prop",
          "initialAnimation": null
        }
      ]
    }
    """
        .trimIndent() + "\n"
