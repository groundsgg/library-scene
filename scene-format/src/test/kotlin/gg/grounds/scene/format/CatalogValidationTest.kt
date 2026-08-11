package gg.grounds.scene.format

import gg.grounds.scene.format.SceneProblemCode.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogValidationTest {
    @Test
    fun `catalog validation rejects exact pin mismatch wrong kind animation and action arguments`() {
        val result = SceneValidation.validateCatalogs(sceneFixture(), assetCatalogMismatch(), actionCatalogMismatch())

        assertEquals(
            setOf(UNKNOWN_ASSET, WRONG_ASSET_KIND, UNKNOWN_ANIMATION, UNKNOWN_ACTION, INVALID_ACTION_ARGUMENT),
            result.problems.map(SceneProblem::code).toSet(),
        )
    }

    @Test
    fun `catalog defaults and application arguments obey their parameter constraints`() {
        assertFailsWith<IllegalArgumentException> {
            ActionParameter(LocalId("count"), ActionParameterType.LONG, true, LongArgument(4), LongConstraints(5, 10))
        }
        val action = ApplicationAction(ActionKey("grounds:apply"), mapOf(LocalId("mode") to EnumArgument(LocalId("wrong"))))
        val result = SceneValidation.validateCatalogs(sceneFixture(actions = listOf(action)), assetCatalog(), actionCatalog())
        assertEquals(true, result.problems.any { it.code == INVALID_ACTION_ARGUMENT })
    }

    @Test
    fun `application action argument limit is enforced`() {
        val arguments = (0..64).associate { LocalId("a$it") to StringArgument("x") }
        val result = SceneValidation.validateCatalogs(
            sceneFixture(actions = listOf(ApplicationAction(ActionKey("grounds:apply"), arguments))), assetCatalog(), actionCatalog(),
        )
        assertEquals(true, result.problems.any { it.code == LIMIT_EXCEEDED })
    }

    private fun sceneFixture(actions: List<SceneAction> = listOf(
        StartAnimationAction(ElementTarget(LocalId("prop"), null), LocalId("missing")),
        PlaySoundAction(AssetKey("grounds:missing-sound"), 1.0, 1.0),
        ApplicationAction(ActionKey("grounds:missing"), emptyMap()),
        ApplicationAction(ActionKey("grounds:apply"), mapOf(LocalId("count") to StringArgument("bad"))),
    )) = SceneDocument(1, SceneId("grounds:test"), SceneMetadata("Test", null, emptySet()),
        SceneCatalogReferences(CatalogReference(CatalogId("grounds:assets"), "1"), CatalogReference(CatalogId("grounds:actions"), "1")), emptyList(),
        listOf(Prop(LocalId("prop"), null, transform(), asset = AssetKey("grounds:prop"), initialAnimation = LocalId("missing")), Npc(LocalId("npc"), null, transform(), body = AssetKey("grounds:prop"), label = null, labelOffset = ORIGIN, look = LookBehavior.Fixed, initialAnimation = null, interactionBounds = LocalBounds(ORIGIN, Vec3(1.0, 1.0, 1.0)), proximity = null, bindings = listOf(TriggerBinding(SceneTrigger.LEFT_CLICK, emptyList(), 0, 0, actions)))),
    )

    private fun assetCatalogMismatch() = assetCatalog(id = CatalogId("grounds:other"), version = "2")
    private fun assetCatalog(id: CatalogId = CatalogId("grounds:assets"), version: String = "1") = AssetCatalog(id, version,
        CatalogVersionRange(CatalogId("grounds:pack"), "1", "1"), mapOf(AssetKey("grounds:prop") to AssetDefinition(AssetKey("grounds:prop"), AssetKind.NPC_BODY, emptySet(), null, emptyMap())))
    private fun actionCatalogMismatch() = actionCatalog(id = CatalogId("grounds:other-actions"), version = "2")
    private fun actionCatalog(id: CatalogId = CatalogId("grounds:actions"), version: String = "1") = ActionCatalog(id, version, mapOf(
        ActionKey("grounds:apply") to ActionDefinition(ActionKey("grounds:apply"), "Apply", "", mapOf(
            LocalId("count") to ActionParameter(LocalId("count"), ActionParameterType.LONG, true, null, LongConstraints(0, 3)),
            LocalId("mode") to ActionParameter(LocalId("mode"), ActionParameterType.ENUM, false, null, EnumConstraints(setOf(LocalId("right")))),
        )),
    ))
    private fun transform() = Transform(ORIGIN, ZERO_ROTATION, Vec3(1.0, 1.0, 1.0))
}
