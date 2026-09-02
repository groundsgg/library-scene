package gg.grounds.scene.minestom

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.internal.RendererCapabilityKey
import gg.grounds.scene.minestom.internal.SceneReadiness
import gg.grounds.scene.minestom.internal.SceneReadinessRequest
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

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
        val orderingKeys =
            problems.map {
                "${it.path}\u0000${it.code.name}\u0000${it.elementId?.value.orEmpty()}\u0000${it.message}"
            }
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

    @Test
    fun `prepares immutable renderer and action capability snapshots`() {
        val rendererFactory = SceneAssetRendererFactory { CompletableFuture() }
        val actionHandler = SceneActionHandler {
            CompletableFuture.completedFuture(SceneActionResult.Success)
        }
        val rendererLookups = mutableMapOf<Pair<AssetKey, AssetKind>, Int>()
        val actionLookups = mutableMapOf<ActionKey, Int>()
        val renderers = SceneAssetRendererRegistry { asset, kind ->
            val key = asset to kind
            val invocation = rendererLookups.merge(key, 1, Int::plus)!!
            rendererFactory.takeIf { invocation == 1 }
        }
        val actions = SceneActionRegistry { key ->
            val invocation = actionLookups.merge(key, 1, Int::plus)!!
            actionHandler.takeIf { invocation == 1 }
        }

        val readiness =
            SceneReadiness.prepare(
                request(
                    renderers,
                    RecordingEffects(setOf(AssetKind.SOUND, AssetKind.PARTICLE)),
                    actions,
                )
            )

        assertEquals(emptyList(), readiness.problems)
        val capabilities = assertNotNull(readiness.capabilities)
        assertSame(
            rendererFactory,
            capabilities.rendererFactories[
                    RendererCapabilityKey(AssetKey("test:prop"), AssetKind.PROP)],
        )
        assertSame(actionHandler, capabilities.actionHandlers[ActionKey("test:application")])
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (capabilities.rendererFactories
                    as MutableMap<RendererCapabilityKey, SceneAssetRendererFactory>)
                .clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (capabilities.actionHandlers as MutableMap<ActionKey, SceneActionHandler>).clear()
        }
    }

    @Test
    fun `rejects runtime identity for a different scene before preparation`() {
        val readiness =
            SceneReadiness.prepare(
                request(
                        RecordingRenderers(setOf(AssetKind.PROP, AssetKind.NPC_BODY)),
                        RecordingEffects(setOf(AssetKind.SOUND, AssetKind.PARTICLE)),
                        RecordingActions(setOf(ActionKey("test:application"))),
                    )
                    .copy(identity = SceneRuntimeIdentity(SceneId("test:other"), "map", 1))
            )

        assertEquals(
            listOf(SceneRuntimeProblemCode.INVALID_CONFIG),
            readiness.problems.map(SceneRuntimeProblem::code),
        )
        assertEquals("identity/sceneId", readiness.problems.single().path)
    }

    @Test
    fun `scene id mismatch returns before every capability lookup`() {
        var rendererLookups = 0
        var effectLookups = 0
        var actionLookups = 0
        val readiness =
            SceneReadiness.prepare(
                request(
                        SceneAssetRendererRegistry { _, _ ->
                            rendererLookups++
                            error("renderer lookup must not happen")
                        },
                        object : SceneEffectSink {
                            override fun supports(asset: AssetKey, kind: AssetKind): Boolean {
                                effectLookups++
                                error("effect lookup must not happen")
                            }

                            override fun playSound(
                                player: net.minestom.server.entity.Player,
                                sound: AssetKey,
                                volume: Double,
                                pitch: Double,
                            ) = error("effect execution must not happen")

                            override fun emitParticle(
                                instance: net.minestom.server.instance.Instance,
                                particle: AssetKey,
                                point: net.minestom.server.coordinate.Point,
                                count: Int,
                                offset: Vec3,
                                speed: Double,
                            ) = error("effect execution must not happen")
                        },
                        SceneActionRegistry {
                            actionLookups++
                            error("action lookup must not happen")
                        },
                    )
                    .copy(identity = SceneRuntimeIdentity(SceneId("test:other"), "map", 1))
            )

        assertEquals(
            listOf(SceneRuntimeProblemCode.INVALID_CONFIG),
            readiness.problems.map { it.code },
        )
        assertEquals("identity/sceneId", readiness.problems.single().path)
        assertEquals(0, rendererLookups)
        assertEquals(0, effectLookups)
        assertEquals(0, actionLookups)
        val capabilities = assertNotNull(readiness.capabilities)
        assertEquals(emptyMap(), capabilities.rendererFactories)
        assertEquals(emptyMap(), capabilities.actionHandlers)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (capabilities.rendererFactories
                    as MutableMap<RendererCapabilityKey, SceneAssetRendererFactory>)
                .clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (capabilities.actionHandlers as MutableMap<ActionKey, SceneActionHandler>).clear()
        }
    }

    @Test
    fun `invisible elements require no renderer capability`() {
        val base = scene()
        val invisibleScene =
            SceneDocument(
                base.schemaVersion,
                base.id,
                base.metadata,
                base.catalogs,
                base.groups,
                base.elements.map(::invisible),
            )
        val readiness =
            SceneReadiness.prepare(
                request(
                        RecordingRenderers(emptySet()),
                        RecordingEffects(setOf(AssetKind.SOUND, AssetKind.PARTICLE)),
                        RecordingActions(setOf(ActionKey("test:application"))),
                    )
                    .copy(scene = invisibleScene)
            )

        assertEquals(emptyList(), readiness.problems)
        assertEquals(emptyMap(), readiness.capabilities!!.rendererFactories)
    }

    private fun assertMissing(
        expected: SceneRuntimeProblemCode,
        renderers: RecordingRenderers,
        effects: RecordingEffects,
        actions: RecordingActions,
    ) {
        assertEquals(
            listOf(expected),
            SceneReadiness.check(request(renderers, effects, actions))
                .map(SceneRuntimeProblem::code)
                .distinct(),
        )
    }

    private fun request(
        renderers: SceneAssetRendererRegistry,
        effects: SceneEffectSink,
        actions: SceneActionRegistry,
    ) =
        SceneReadinessRequest(
            scene = scene(),
            assets = assetCatalog(),
            actions = actionCatalog(),
            renderers = renderers,
            effects = effects,
            actionRegistry = actions,
            identity = SceneRuntimeIdentity(scene().id, "map", 1),
            config = SceneRuntimeConfig(),
        )

    private fun scene() =
        SceneDocument(
            schemaVersion = 1,
            id = SceneId("test:readiness"),
            metadata = SceneMetadata("Readiness", null, emptySet()),
            catalogs =
                SceneCatalogReferences(
                    CatalogReference(CatalogId("test:assets"), "1"),
                    CatalogReference(CatalogId("test:actions"), "1"),
                ),
            groups = emptyList(),
            elements =
                listOf(
                    Prop(
                        LocalId("renderer-prop"),
                        null,
                        transform(),
                        asset = AssetKey("test:prop"),
                        initialAnimation = null,
                    ),
                    CompositeProp(
                        LocalId("renderer-composite"),
                        null,
                        transform(),
                        parts =
                            listOf(
                                CompositePart(LocalId("part"), AssetKey("test:part"), transform())
                            ),
                    ),
                    Npc(
                        LocalId("action-npc"),
                        null,
                        transform(),
                        body = AssetKey("test:npc"),
                        label = null,
                        labelOffset = Vec3(0.0, 1.0, 0.0),
                        look = LookBehavior.Fixed,
                        initialAnimation = null,
                        interactionBounds = LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                        proximity = null,
                        bindings =
                            listOf(
                                TriggerBinding(
                                    SceneTrigger.RIGHT_CLICK,
                                    emptyList(),
                                    0,
                                    0,
                                    listOf(
                                        ApplicationAction(
                                            ActionKey("test:application"),
                                            emptyMap(),
                                        ),
                                        PlaySoundAction(AssetKey("test:sound"), 1.0, 1.0),
                                        EmitParticleAction(
                                            ElementTarget(LocalId("action-npc"), null),
                                            AssetKey("test:particle"),
                                            1,
                                            Vec3(0.0, 0.0, 0.0),
                                            0.0,
                                        ),
                                    ),
                                )
                            ),
                    ),
                ),
        )

    private fun assetCatalog() =
        AssetCatalog(
            CatalogId("test:assets"),
            "1",
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
            CatalogId("test:actions"),
            "1",
            mapOf(
                ActionKey("test:application") to
                    ActionDefinition(ActionKey("test:application"), "Application", "", emptyMap())
            ),
        )

    private fun transform() =
        Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))

    private fun invisible(element: SceneElement): SceneElement =
        when (element) {
            is Prop -> element.copy(visible = false)
            is CompositeProp ->
                CompositeProp(
                    element.id,
                    element.group,
                    element.transform,
                    false,
                    element.activation,
                    element.parts,
                )
            is Npc ->
                Npc(
                    element.id,
                    element.group,
                    element.transform,
                    false,
                    element.activation,
                    element.body,
                    element.label,
                    element.labelOffset,
                    element.look,
                    element.initialAnimation,
                    element.interactionBounds,
                    element.proximity,
                    element.bindings,
                )
        }

    private class RecordingRenderers(private val supported: Set<AssetKind>) :
        SceneAssetRendererRegistry {
        var factoryCalls = 0

        override fun rendererFor(asset: AssetKey, kind: AssetKind): SceneAssetRendererFactory? =
            if (kind in supported)
                SceneAssetRendererFactory {
                    factoryCalls++
                    error("must not create")
                }
            else null
    }

    private class RecordingEffects(private val supported: Set<AssetKind>) : SceneEffectSink {
        var effectCalls = 0

        override fun supports(asset: AssetKey, kind: AssetKind) = kind in supported

        override fun playSound(
            player: net.minestom.server.entity.Player,
            sound: AssetKey,
            volume: Double,
            pitch: Double,
        ) {
            effectCalls++
        }

        override fun emitParticle(
            instance: net.minestom.server.instance.Instance,
            particle: AssetKey,
            point: net.minestom.server.coordinate.Point,
            count: Int,
            offset: Vec3,
            speed: Double,
        ) {
            effectCalls++
        }
    }

    private class RecordingActions(private val supported: Set<ActionKey>) : SceneActionRegistry {
        var handlerCalls = 0

        override fun handlerFor(key: ActionKey): SceneActionHandler? =
            if (key in supported)
                SceneActionHandler {
                    handlerCalls++
                    error("must not execute")
                }
            else null
    }
}
