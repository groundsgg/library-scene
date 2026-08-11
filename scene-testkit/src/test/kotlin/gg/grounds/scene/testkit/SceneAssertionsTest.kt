package gg.grounds.scene.testkit

import gg.grounds.scene.format.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SceneAssertionsTest {
    @Test
    fun `validity assertion accepts a valid minimal fixture`() {
        assertValidScene(SceneFixtures.minimal())
    }

    @Test
    fun `validity assertion prints every ordered problem`() {
        val error = assertFailsWith<AssertionError> { assertValidScene(invalidSceneFixture()) }

        assertEquals(
            """
            Scene validation failed:
            - path=groups/group code=DUPLICATE_GROUP_ID identity=group message=Identifier is duplicated in this scope.
            - path=schemaVersion code=UNSUPPORTED_SCHEMA_VERSION identity=test:minimal message=Schema version must be 1.
            """
                .trimIndent(),
            error.message,
        )
    }

    @Test
    fun `catalog assertion accepts a fixture with matching catalogs`() {
        assertCatalogCompatible(SceneFixtures.minimal(), matchingAssets(), matchingActions())
    }

    @Test
    fun `catalog assertion reports catalog incompatibility`() {
        val error =
            assertFailsWith<AssertionError> {
                assertCatalogCompatible(
                    SceneFixtures.minimal(),
                    incompatibleAssets(),
                    matchingActions(),
                )
            }

        assertTrue(error.message!!.contains("UNKNOWN_ASSET"))
    }

    @Test
    fun `canonical assertion accepts canonical bytes`() {
        val scene = SceneFixtures.complete()
        val expected = (SceneJson.encode(scene) as SceneEncodeResult.Success).bytes

        assertCanonicalScene(scene, expected)
    }

    @Test
    fun `canonical assertion rejects different bytes with hashes and first differing offset`() {
        val scene = SceneFixtures.minimal()
        val expected = (SceneJson.encode(scene) as SceneEncodeResult.Success).bytes
        val altered = expected.copyOf().also { it[0] = 'X'.code.toByte() }

        val error = assertFailsWith<AssertionError> { assertCanonicalScene(scene, altered) }

        assertTrue(error.message!!.contains("expected(size=${altered.size}, sha256="))
        assertTrue(error.message!!.contains("actual(size=${expected.size}, sha256="))
        assertTrue(error.message!!.contains("firstDifference=0"))
    }

    @Test
    fun `complete fixture has deterministic canonical output`() {
        val first = (SceneJson.encode(SceneFixtures.complete()) as SceneEncodeResult.Success).bytes
        val second = (SceneJson.encode(SceneFixtures.complete()) as SceneEncodeResult.Success).bytes

        assertContentEquals(first, second)
    }

    @Test
    fun `canonical assertion detects a mutated fixture`() {
        val original = SceneFixtures.minimal()
        val expected = (SceneJson.encode(original) as SceneEncodeResult.Success).bytes
        val mutated =
            SceneDocument(
                original.schemaVersion,
                original.id,
                original.metadata,
                original.catalogs,
                original.groups,
                listOf(
                    Prop(
                        LocalId("prop"),
                        null,
                        transform(),
                        asset = AssetKey("test:other-prop"),
                        initialAnimation = null,
                    )
                ),
            )

        assertFailsWith<AssertionError> { assertCanonicalScene(mutated, expected) }
    }

    @Test
    fun `fixtures return independent immutable valid scenes`() {
        val first = SceneFixtures.complete()
        val second = SceneFixtures.complete()

        assertTrue(SceneValidation.validateIntrinsic(first).isValid)
        assertTrue(SceneValidation.validateIntrinsic(second).isValid)
        assertTrue(first !== second)
        assertTrue(first.elements !== second.elements)
        assertFailsWith<UnsupportedOperationException> {
            (first.elements as MutableList<SceneElement>).clear()
        }
        assertEquals(4, second.elements.size)
    }

    private fun invalidSceneFixture() =
        SceneDocument(
            2,
            SceneId("test:minimal"),
            SceneMetadata("Minimal", null, emptySet()),
            SceneCatalogReferences(
                CatalogReference(CatalogId("test:assets"), "1"),
                CatalogReference(CatalogId("test:actions"), "1"),
            ),
            listOf(SceneGroup(LocalId("group"), "One"), SceneGroup(LocalId("group"), "Two")),
            emptyList(),
        )

    private fun matchingAssets() =
        AssetCatalog(
            CatalogId("test:assets"),
            "1",
            CatalogVersionRange(CatalogId("test:pack"), "1", "1"),
            mapOf(
                AssetKey("test:prop") to
                    AssetDefinition(
                        AssetKey("test:prop"),
                        AssetKind.PROP,
                        emptySet(),
                        null,
                        emptyMap(),
                    )
            ),
        )

    private fun incompatibleAssets() =
        AssetCatalog(
            CatalogId("test:assets"),
            "1",
            CatalogVersionRange(CatalogId("test:pack"), "1", "1"),
            emptyMap(),
        )

    private fun matchingActions() = ActionCatalog(CatalogId("test:actions"), "1", emptyMap())

    private fun transform() = Transform(ORIGIN, ZERO_ROTATION, Vec3(1.0, 1.0, 1.0))
}
