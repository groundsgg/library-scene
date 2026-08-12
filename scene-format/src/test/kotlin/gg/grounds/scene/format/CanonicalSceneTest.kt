package gg.grounds.scene.format

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CanonicalSceneTest {
    @Test
    fun `semantically unordered collections produce identical canonical bytes`() {
        val original =
            assertIs<SceneDecodeResult.Success>(
                    SceneJson.decode(completeJson().encodeToByteArray())
                )
                .scene
        val reordered =
            SceneDocument(
                original.schemaVersion,
                original.id,
                SceneMetadata(
                    original.metadata.name,
                    original.metadata.description,
                    original.metadata.tags.reversed().toSet(),
                ),
                original.catalogs,
                original.groups.reversed(),
                original.elements.reversed().map { element ->
                    if (element !is Npc || element.id.value != "tracked") element
                    else {
                        val binding = element.bindings.single()
                        Npc(
                            element.id,
                            element.group,
                            element.transform,
                            element.visible,
                            element.activation,
                            element.body,
                            element.label,
                            element.labelOffset,
                            element.look,
                            element.initialAnimation,
                            element.interactionBounds,
                            element.proximity,
                            listOf(
                                TriggerBinding(
                                    binding.trigger,
                                    binding.conditions.reversed(),
                                    binding.cooldownMillis,
                                    binding.debounceMillis,
                                    binding.actions,
                                )
                            ),
                        )
                    }
                },
            )

        val first = assertIs<SceneEncodeResult.Success>(SceneJson.encode(original)).bytes
        val second = assertIs<SceneEncodeResult.Success>(SceneJson.encode(reordered)).bytes

        assertContentEquals(first, second)
        assertContentEquals(
            MessageDigest.getInstance("SHA-256").digest(first),
            MessageDigest.getInstance("SHA-256").digest(second),
        )
    }

    @Test
    fun `binding actions preserve authored order`() {
        val scene =
            assertIs<SceneDecodeResult.Success>(
                    SceneJson.decode(completeJson().encodeToByteArray())
                )
                .scene
        val decoded =
            assertIs<SceneDecodeResult.Success>(
                    SceneJson.decode(
                        assertIs<SceneEncodeResult.Success>(SceneJson.encode(scene)).bytes
                    )
                )
                .scene
        val actions =
            assertIs<Npc>(decoded.elements.single { it.id.value == "tracked" })
                .bindings
                .single()
                .actions

        assertEquals(
            listOf(StartAnimationAction::class, StopAnimationAction::class, PlaySoundAction::class),
            actions.take(3).map { it::class },
        )
    }
}
