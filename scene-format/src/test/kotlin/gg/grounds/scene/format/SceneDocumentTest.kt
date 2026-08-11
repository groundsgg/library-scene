package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneDocumentTest {
    @Test
    fun `document snapshots and sorts semantic metadata only`() {
        val tags = mutableSetOf("z", "a")
        val scene = sceneDocument(metadata = SceneMetadata("Lobby", null, tags))
        tags.clear()
        assertEquals(listOf("a", "z"), scene.metadata.tags.toList())
    }

    @Test
    fun `document snapshots flat groups and elements without transforms`() {
        val groups = mutableListOf(SceneGroup(LocalId("entry"), "Entry"))
        val elements = mutableListOf<SceneElement>()
        val scene = sceneDocument(groups = groups, elements = elements)
        groups.clear()
        elements.clear()
        assertEquals(listOf("entry"), scene.groups.map { it.id.value })
        assertEquals(emptyList(), scene.elements)
    }

    @Test
    fun `failure results require non-empty sorted immutable diagnostics`() {
        val later = SceneProblem("/z", SceneProblemCode.INVALID_SCALE, null, "bad")
        val earlier = SceneProblem("/a", SceneProblemCode.INVALID_BOUNDS, null, "bad")
        assertEquals(listOf(earlier, later), SceneDecodeResult.Failure(listOf(later, earlier)).problems)
        assertFailsWith<IllegalArgumentException> { SceneEncodeResult.Failure(emptyList()) }
    }

    @Test
    fun `validation reports whether its diagnostics are empty`() {
        assertTrue(SceneValidationResult(emptyList()).isValid)
        assertFalse(SceneValidationResult(listOf(SceneProblem("/x", SceneProblemCode.INVALID_SCALE, null, "bad"))).isValid)
    }

    private fun sceneDocument(
        metadata: SceneMetadata = SceneMetadata("Lobby", null, emptySet()),
        groups: List<SceneGroup> = emptyList(),
        elements: List<SceneElement> = emptyList(),
    ) = SceneDocument(
        schemaVersion = 1,
        id = SceneId("grounds:lobby"),
        metadata = metadata,
        catalogs = SceneCatalogReferences(
            assets = CatalogReference(CatalogId("grounds:assets"), "1"),
            actions = CatalogReference(CatalogId("grounds:actions"), "1"),
        ),
        groups = groups,
        elements = elements,
    )
}

object CollectionImmutabilityFixtures {
    @JvmStatic
    fun document(): SceneDocument = SceneDocument(
        schemaVersion = 1,
        id = SceneId("grounds:lobby"),
        metadata = SceneMetadata("Lobby", null, linkedSetOf("z", "a")),
        catalogs = SceneCatalogReferences(
            assets = CatalogReference(CatalogId("grounds:assets"), "1"),
            actions = CatalogReference(CatalogId("grounds:actions"), "1"),
        ),
        groups = listOf(group()),
        elements = emptyList(),
    )

    @JvmStatic
    fun group(): SceneGroup = SceneGroup(LocalId("entry"), "Entry")
}
