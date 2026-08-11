package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
