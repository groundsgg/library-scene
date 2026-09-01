package gg.grounds.scene.minestom

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.internal.SceneReadiness
import gg.grounds.scene.minestom.internal.SceneReadinessRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class SceneReadinessTest {
    @Test
    fun `reports every missing host capability in stable order without invoking it`() {
        val renderers = RecordingRenderers(emptySet())
        val effects = RecordingEffects(emptySet())
        val actions = RecordingActions(emptySet())

        val problems = SceneReadiness.check(request(renderers, effects, actions))

        assertEquals(
            listOf(
                SceneRuntimeProblemCode.MISSING_ACTION_HANDLER,
                SceneRuntimeProblemCode.MISSING_EFFECT,
                SceneRuntimeProblemCode.MISSING_RENDERER,
            ),
            problems.map(SceneRuntimeProblem::code).distinct(),
        )
        val orderingKeys = problems.map { "${it.path}\u0000${it.code.name}\u0000${it.elementId?.value.orEmpty()}\u0000${it.message}" }
        assertEquals(orderingKeys.sorted(), orderingKeys)
        assertEquals(0, renderers.factoryCalls)
        assertEquals(0, effects.effectCalls)
        assertEquals(0, actions.handlerCalls)
    }

    @Test
    fun `accepts complete host capabilities`() {
        assertEquals(
            emptyList(),
            SceneReadiness.check(
                request(
                    RecordingRenderers(setOf(AssetKind.PROP, AssetKind.NPC_BODY)),
                    RecordingEffects(setOf(AssetKind.SOUND, AssetKind.PARTICLE)),
                    RecordingActions(setOf(ActionKey("test:application"))),
                )
            ),
        )
    }

    @Test
    fun `reports each independently missing host capability`() {
        assertMissing(
            SceneRuntimeProblemCode.MISSING_RENDERER,
            RecordingRenderers(setOf(AssetKind.NPC_BODY)),
            RecordingEffects(setOf(AssetKind.SOUND, AssetKind.PARTICLE)),
            RecordingActions(setOf(ActionKey("test:application"))),
        )
        assertMissing(
            SceneRuntimeProblemCode.MISSING_RENDERER,
            RecordingRenderers(setOf(AssetKind.PROP)),
            RecordingEffects(setOf(AssetKind.SOUND, AssetKind.PARTICLE)),
            RecordingActions(setOf(ActionKey("test:application"))),
        )
        assertMissing(
            SceneRuntimeProblemCode.MISSING_EFFECT,
            RecordingRenderers(setOf(AssetKind.PROP, AssetKind.NPC_BODY)),
            RecordingEffects(setOf(AssetKind.PARTICLE)),
            RecordingActions(setOf(ActionKey("test:application"))),
        )
        assertMissing(
            SceneRuntimeProblemCode.MISSING_EFFECT,
            RecordingRenderers(setOf(AssetKind.PROP, AssetKind.NPC_BODY)),
            RecordingEffects(setOf(AssetKind.SOUND)),
            RecordingActions(setOf(ActionKey("test:application"))),
        )
        assertMissing(
            SceneRuntimeProblemCode.MISSING_ACTION_HANDLER,
            RecordingRenderers(setOf(AssetKind.PROP, AssetKind.NPC_BODY)),
            RecordingEffects(setOf(AssetKind.SOUND, AssetKind.PARTICLE)),
            RecordingActions(emptySet()),
        )
    }

    private fun assertMissing(
        expected: SceneRuntimeProblemCode,
        renderers: RecordingRenderers,
        effects: RecordingEffects,
        actions: RecordingActions,
    ) {
        assertEquals(listOf(expected), SceneReadiness.check(request(renderers, effects, actions)).map(SceneRuntimeProblem::code).distinct())
    }

    private fun request(
        renderers: RecordingRenderers,
        effects: RecordingEffects,
        actions: RecordingActions,
    ) =
        SceneReadinessRequest(
            scene = scene(),
            assets = assetCatalog(),
            actions = actionCatalog(),
            renderers = renderers,
            effects = effects,
            actionRegistry = actions,
            config = SceneRuntimeConfig(),
        )

    private fun scene() =
        SceneDocument(
            schemaVersion = 1,
            id = SceneId("test:readiness"),
            metadata = SceneMetadata("Readiness", null, emptySet()),
            catalogs = SceneCatalogReferences(
                CatalogReference(CatalogId("test:assets"), "1"),
                CatalogReference(CatalogId("test:actions"), "1"),
            ),
            groups = emptyList(),
            elements = listOf(
                Prop(LocalId("renderer-prop"), null, transform(), asset = AssetKey("test:prop"), initialAnimation = null),
                CompositeProp(
                    LocalId("renderer-composite"), null, transform(),
                    parts = listOf(CompositePart(LocalId("part"), AssetKey("test:part"), transform())),
                ),
                Npc(
                    LocalId("action-npc"), null, transform(),
                    body = AssetKey("test:npc"), label = null, labelOffset = Vec3(0.0, 1.0, 0.0),
                    look = LookBehavior.Fixed, initialAnimation = null,
                    interactionBounds = LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                    proximity = null,
                    bindings = listOf(
                        TriggerBinding(
                            SceneTrigger.RIGHT_CLICK, emptyList(), 0, 0,
                            listOf(
                                ApplicationAction(ActionKey("test:application"), emptyMap()),
                                PlaySoundAction(AssetKey("test:sound"), 1.0, 1.0),
                                EmitParticleAction(
                                    ElementTarget(LocalId("action-npc"), null), AssetKey("test:particle"), 1,
                                    Vec3(0.0, 0.0, 0.0), 0.0,
                                ),
                            ),
                        )
                    ),
                ),
            ),
        )

    private fun assetCatalog() =
        AssetCatalog(
            CatalogId("test:assets"), "1",
            CatalogVersionRange(CatalogId("test:assets"), "1", "1"),
            mapOf(
                AssetKey("test:prop") to definition("test:prop", AssetKind.PROP),
                AssetKey("test:part") to definition("test:part", AssetKind.PROP),
                AssetKey("test:npc") to definition("test:npc", AssetKind.NPC_BODY),
                AssetKey("test:sound") to definition("test:sound", AssetKind.SOUND),
                AssetKey("test:particle") to definition("test:particle", AssetKind.PARTICLE),
            ),
        )

    private fun definition(key: String, kind: AssetKind) =
        AssetDefinition(AssetKey(key), kind, emptySet(), null, emptyMap())

    private fun actionCatalog() =
        ActionCatalog(
            CatalogId("test:actions"), "1",
            mapOf(
                ActionKey("test:application") to
                    ActionDefinition(ActionKey("test:application"), "Application", "", emptyMap()),
            ),
        )

    private fun transform() = Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))

    private class RecordingRenderers(private val supported: Set<AssetKind>) : SceneAssetRendererRegistry {
        var factoryCalls = 0
        override fun rendererFor(asset: AssetKey, kind: AssetKind): SceneAssetRendererFactory? =
            if (kind in supported) SceneAssetRendererFactory { factoryCalls++; error("must not create") } else null
    }

    private class RecordingEffects(private val supported: Set<AssetKind>) : SceneEffectSink {
        var effectCalls = 0
        override fun supports(asset: AssetKey, kind: AssetKind) = kind in supported
        override fun playSound(player: net.minestom.server.entity.Player, sound: AssetKey, volume: Double, pitch: Double) { effectCalls++ }
        override fun emitParticle(instance: net.minestom.server.instance.Instance, particle: AssetKey, point: net.minestom.server.coordinate.Point, count: Int, offset: Vec3, speed: Double) { effectCalls++ }
    }

    private class RecordingActions(private val supported: Set<ActionKey>) : SceneActionRegistry {
        var handlerCalls = 0
        override fun handlerFor(key: ActionKey): SceneActionHandler? =
            if (key in supported) SceneActionHandler { handlerCalls++; error("must not execute") } else null
    }
}
