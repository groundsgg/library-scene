package gg.grounds.scene.format

import gg.grounds.scene.format.SceneProblemCode.*
import kotlin.test.Test
import kotlin.test.assertEquals

class IntrinsicValidationTest {
    @Test
    fun `intrinsic validation reports every ordered failure`() {
        val result = SceneValidation.validateIntrinsic(
            scene(
                groups = listOf(SceneGroup(LocalId("known"), "Known")),
                elements = listOf(
                    Prop(LocalId("duplicate"), LocalId("missing"), transform(), asset = AssetKey("grounds:prop"), initialAnimation = null),
                    Prop(LocalId("duplicate"), null, transform(), asset = AssetKey("grounds:prop"), initialAnimation = null),
                    npc(proximity = null, bindings = listOf(binding(SceneTrigger.PROXIMITY_ENTER))),
                ),
            ),
        )

        assertEquals(
            listOf(DUPLICATE_ELEMENT_ID, MISSING_GROUP, MISSING_PROXIMITY_SENSOR),
            result.problems.map(SceneProblem::code),
        )
        assertEquals(result.problems.sortedWith(SceneProblem.ORDERING), result.problems)
    }

    @Test
    fun `intrinsic validation reports limits without truncating later failures`() {
        val groups = (0..4096).map { SceneGroup(LocalId("g$it"), "Group") }
        val parts = (0..4096).map { CompositePart(LocalId("p$it"), AssetKey("grounds:prop"), transform()) }
        val bindings = (0..128).map { binding(SceneTrigger.LEFT_CLICK) }
        val actions = (0..128).map { SendMessageAction(net.kyori.adventure.text.Component.text("x")) }
        val result = SceneValidation.validateIntrinsic(
            scene(
                groups = groups,
                elements = listOf(
                    CompositeProp(LocalId("empty"), null, transform(), parts = emptyList()),
                    CompositeProp(LocalId("many-parts"), null, transform(), parts = parts),
                    npc(bindings = bindings),
                    npc(id = LocalId("many-actions"), bindings = listOf(TriggerBinding(SceneTrigger.LEFT_CLICK, emptyList(), 0, 0, actions))),
                    Prop(LocalId("later"), LocalId("absent"), transform(), asset = AssetKey("grounds:prop"), initialAnimation = null),
                ),
            ),
        )

        assertEquals(5, result.problems.count { it.code == LIMIT_EXCEEDED })
        assertEquals(true, result.problems.any { it.code == MISSING_GROUP && it.path == "elements/later/group" })
    }

    @Test
    fun `binding limit accepts 128 and reports one problem at 129`() {
        val atLimit = SceneValidation.validateIntrinsic(scene(elements = listOf(npc(bindings = (1..128).map { binding(SceneTrigger.LEFT_CLICK) }))))
        val oneOver = SceneValidation.validateIntrinsic(scene(elements = listOf(npc(bindings = (1..129).map { binding(SceneTrigger.LEFT_CLICK) }))))

        assertEquals(emptyList(), atLimit.problems.filter { it.path == "elements/npc/bindings" })
        assertEquals(listOf(LIMIT_EXCEEDED), oneOver.problems.filter { it.path == "elements/npc/bindings" }.map(SceneProblem::code))
    }

    @Test
    fun `intrinsic validation deduplicates identical missing group diagnostics`() {
        val result = SceneValidation.validateIntrinsic(scene(elements = listOf(
            Prop(LocalId("duplicate"), LocalId("missing"), transform(), asset = AssetKey("grounds:prop"), initialAnimation = null),
            Prop(LocalId("duplicate"), LocalId("missing"), transform(), asset = AssetKey("grounds:prop"), initialAnimation = null),
        )))

        assertEquals(1, result.problems.count { it.code == MISSING_GROUP && it.path == "elements/duplicate/group" })
        assertEquals(result.problems.sortedWith(SceneProblem.ORDERING), result.problems)
    }

    private fun scene(groups: List<SceneGroup> = emptyList(), elements: List<SceneElement>): SceneDocument = SceneDocument(
        schemaVersion = 1,
        id = SceneId("grounds:test"),
        metadata = SceneMetadata("Test", null, emptySet()),
        catalogs = SceneCatalogReferences(CatalogReference(CatalogId("grounds:assets"), "1"), CatalogReference(CatalogId("grounds:actions"), "1")),
        groups = groups,
        elements = elements,
    )

    private fun npc(
        id: LocalId = LocalId("npc"),
        proximity: ProximitySensor? = ProximitySensor(1.0, 2.0),
        bindings: List<TriggerBinding> = emptyList(),
    ) = Npc(id, null, transform(), body = AssetKey("grounds:npc"), label = null, labelOffset = ORIGIN, look = LookBehavior.Fixed,
        initialAnimation = null, interactionBounds = LocalBounds(ORIGIN, Vec3(1.0, 1.0, 1.0)), proximity = proximity, bindings = bindings)

    private fun binding(trigger: SceneTrigger) = TriggerBinding(trigger, emptyList(), 0, 0, listOf(SendMessageAction(net.kyori.adventure.text.Component.text("x"))))
    private fun transform() = Transform(ORIGIN, ZERO_ROTATION, Vec3(1.0, 1.0, 1.0))
}
