package gg.grounds.scene.testkit

import gg.grounds.scene.format.*
import java.math.BigDecimal
import net.kyori.adventure.text.Component

object SceneFixtures {
    fun minimal(): SceneDocument =
        SceneDocument(
            1,
            SceneId("test:minimal"),
            SceneMetadata("Minimal", null, emptySet()),
            catalogReferences(),
            emptyList(),
            listOf(
                Prop(
                    LocalId("prop"),
                    null,
                    transform(),
                    asset = AssetKey("test:prop"),
                    initialAnimation = null,
                )
            ),
        )

    fun complete(): SceneDocument =
        SceneDocument(
            1,
            SceneId("test:complete"),
            SceneMetadata("Complete", "all variants", setOf("fixture", "v1")),
            catalogReferences(),
            listOf(SceneGroup(LocalId("actors"), "Actors")),
            listOf(
                CompositeProp(
                    LocalId("composite"),
                    null,
                    transform(),
                    activation = ActivationPolicy.ALWAYS,
                    parts =
                        listOf(CompositePart(LocalId("part"), AssetKey("test:part"), transform())),
                ),
                Npc(
                    LocalId("fixed"),
                    LocalId("actors"),
                    transform(),
                    body = AssetKey("test:npc"),
                    label = null,
                    labelOffset = Vec3(0.0, 1.0, 0.0),
                    look = LookBehavior.Fixed,
                    initialAnimation = null,
                    interactionBounds = bounds(),
                    proximity = null,
                    bindings = emptyList(),
                ),
                Prop(
                    LocalId("prop"),
                    null,
                    transform(),
                    asset = AssetKey("test:prop"),
                    initialAnimation = LocalId("idle"),
                ),
                Npc(
                    LocalId("tracked"),
                    LocalId("actors"),
                    transform(),
                    activation = ActivationPolicy.ALWAYS,
                    body = AssetKey("test:npc"),
                    label = Component.text("Guide"),
                    labelOffset = Vec3(0.0, 1.0, 0.0),
                    look = LookBehavior.TrackNearest(12.0, true, 90.0),
                    initialAnimation = LocalId("idle"),
                    interactionBounds = bounds(),
                    proximity = ProximitySensor(3.0, 4.0),
                    bindings = listOf(completeBinding()),
                ),
            ),
        )

    private fun catalogReferences() =
        SceneCatalogReferences(
            CatalogReference(CatalogId("test:assets"), "1"),
            CatalogReference(CatalogId("test:actions"), "1"),
        )

    private fun transform() = Transform(ORIGIN, ZERO_ROTATION, Vec3(1.0, 1.0, 1.0))

    private fun bounds() = LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0))

    private fun completeBinding() =
        TriggerBinding(
            SceneTrigger.RIGHT_CLICK,
            listOf(
                GameModeCondition(SceneGameMode.ADVENTURE),
                HandCondition(SceneHand.MAIN),
                PermissionCondition("scene.use"),
                SneakingCondition(false),
            ),
            100,
            10,
            listOf(
                StartAnimationAction(ElementTarget(LocalId("prop"), null), LocalId("wave")),
                StopAnimationAction(ElementTarget(LocalId("composite"), LocalId("part")), null),
                PlaySoundAction(AssetKey("test:sound"), 1.0, 1.0),
                SetViewerScaleAction(ElementTarget(LocalId("tracked"), null), 1.25, 250),
                SetViewerHighlightAction(ElementTarget(LocalId("tracked"), null), true, 100),
                SendMessageAction(Component.text("Message")),
                SendActionBarAction(Component.text("Action bar")),
                ShowTitleAction(
                    Component.text("Title"),
                    Component.text("Subtitle"),
                    100,
                    1_000,
                    100,
                ),
                EmitParticleAction(
                    ElementTarget(LocalId("composite"), LocalId("part")),
                    AssetKey("test:particle"),
                    4,
                    Vec3(0.1, 0.2, 0.3),
                    0.5,
                ),
                ApplicationAction(
                    ActionKey("test:application"),
                    linkedMapOf(
                        LocalId("asset") to AssetArgument(AssetKey("test:asset")),
                        LocalId("boolean") to BooleanArgument(true),
                        LocalId("decimal") to DecimalArgument(BigDecimal("12.5")),
                        LocalId("enum") to EnumArgument(LocalId("choice")),
                        LocalId("long") to LongArgument(42),
                        LocalId("string") to StringArgument("value"),
                    ),
                ),
            ),
        )
}
