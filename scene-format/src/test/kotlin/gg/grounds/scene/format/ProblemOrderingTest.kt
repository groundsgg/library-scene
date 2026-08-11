package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemOrderingTest {
    @Test
    fun `validation diagnostics are ordered by path code and message`() {
        val result = SceneValidation.validateIntrinsic(SceneDocument(0, SceneId("grounds:test"), SceneMetadata("Test", null, emptySet()),
            SceneCatalogReferences(CatalogReference(CatalogId("grounds:assets"), "1"), CatalogReference(CatalogId("grounds:actions"), "1")),
            listOf(SceneGroup(LocalId("duplicate"), "One"), SceneGroup(LocalId("duplicate"), "Two")), emptyList()))

        assertEquals(listOf("groups/duplicate", "schemaVersion"), result.problems.map(SceneProblem::path))
    }
}
