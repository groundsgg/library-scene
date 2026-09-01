package gg.grounds.scene.minestom

import gg.grounds.scene.format.*
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SceneRuntimeLifecycleIntegrationTest {
    @Test
    fun `runtime creation events ticking disconnect and close form one atomic lifecycle`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player =
            Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
                it.setInstance(instance, Pos(0.0, 0.0, 0.0, 0.0f, 0.0f)).join()
            }
        val initialChildren = instance.eventNode().children.toSet()
        val initialEntities = instance.entities.toSet()
        val actions = RecordingActions()
        val renderer = RecordingRenderer(instance)
        val request = request(instance, renderer, actions)

        val readinessFailure =
            SceneRuntimeFactory.create(
                    request.copy(renderers = SceneAssetRendererRegistry { _, _ -> null })
                )
                .toCompletableFuture()
        assertTrue(readinessFailure.isDone)
        assertIs<SceneRuntimeCreationResult.Failure>(readinessFailure.get())
        assertEquals(initialChildren, instance.eventNode().children)
        assertEquals(initialEntities, instance.entities)
        assertEquals(0, renderer.createCalls)

        val failingRenderer = RecordingRenderer(instance)
        val activationFailure =
            SceneRuntimeFactory.create(request(instance, failingRenderer, actions))
                .toCompletableFuture()
        tickUntil(instance) { failingRenderer.createCalls == 1 }
        Thread.startVirtualThread {
                failingRenderer.completion.completeExceptionally(
                    IllegalStateException("renderer failed")
                )
            }
            .join()
        assertFalse(activationFailure.isDone)
        tickUntil(instance) { activationFailure.isDone }
        assertEquals(
            listOf(SceneRuntimeProblemCode.ACTIVATION_FAILED),
            assertIs<SceneRuntimeCreationResult.Failure>(activationFailure.get())
                .problems
                .map(SceneRuntimeProblem::code),
        )
        assertEquals(initialChildren, instance.eventNode().children)
        assertEquals(initialEntities, instance.entities)

        val creation = SceneRuntimeFactory.create(request).toCompletableFuture()
        assertFalse(creation.isDone)
        tickUntil(instance) { renderer.createCalls == 1 }
        Thread.startVirtualThread { renderer.completion.complete(renderer.handle) }.join()
        assertFalse(creation.isDone)
        tickUntil(instance) { creation.isDone }
        val runtime = assertIs<SceneRuntimeCreationResult.Success>(creation.get()).runtime
        val installedChildren = instance.eventNode().children - initialChildren
        assertEquals(1, installedChildren.size)
        assertEquals(1, renderer.createCalls)
        val interaction = instance.entities.single { it.entityType == EntityType.INTERACTION }
        val handle = renderer.handle

        val advancesBeforeTick = handle.animationAdvances.size
        instance.tick(0)
        assertEquals(advancesBeforeTick + 1, handle.animationAdvances.size)
        handle.failNextAnimationAdvance = true
        val advancesBeforeFailure = handle.animationAdvances.size
        instance.tick(0)
        assertEquals(advancesBeforeFailure, handle.animationAdvances.size)
        instance.tick(0)
        assertEquals(advancesBeforeFailure + 1, handle.animationAdvances.size)

        player.teleport(Pos(0.0, 0.0, 0.0, 90.0f, 0.0f)).join()
        instance
            .eventNode()
            .call(PlayerEntityInteractEvent(player, interaction, PlayerHand.OFF, Vec.ZERO))
        assertTrue(actions.contexts.isEmpty())

        player.teleport(Pos(0.0, 0.0, 0.0, 0.0f, 0.0f)).join()
        instance
            .eventNode()
            .call(PlayerEntityInteractEvent(player, interaction, PlayerHand.OFF, Vec.ZERO))
        assertEquals(SceneTrigger.RIGHT_CLICK, actions.contexts.single().trigger)
        assertEquals(SceneHand.OFF, actions.contexts.single().hand)

        instance.eventNode().call(EntityAttackEvent(player, interaction))
        assertEquals(
            listOf(SceneTrigger.RIGHT_CLICK, SceneTrigger.LEFT_CLICK),
            actions.contexts.map(SceneActionContext::trigger),
        )
        assertEquals(SceneHand.MAIN, actions.contexts.last().hand)
        assertEquals(2.0, actions.contexts.last().viewerState.scaleMultiplier)

        instance.eventNode().call(PlayerDisconnectEvent(player))
        assertEquals(listOf(player.uuid), handle.clearedViewers)
        actions.firstRightCompletion.complete(SceneActionResult.Success)
        instance.tick(0)
        instance
            .eventNode()
            .call(PlayerEntityInteractEvent(player, interaction, PlayerHand.OFF, Vec.ZERO))
        assertEquals(3, actions.contexts.size)
        assertEquals(1.0, actions.contexts.last().viewerState.scaleMultiplier)

        val close = runtime.close().toCompletableFuture()
        assertSame(close, runtime.close().toCompletableFuture())
        assertFalse(close.isDone)
        tickUntil(instance) { close.isDone }
        close.get()
        assertTrue(runtime.isClosed)
        assertEquals(initialChildren, instance.eventNode().children)
        assertTrue(interaction.isRemoved)
        assertTrue(handle.closed)
        assertTrue(handle.entitiesRemovedWhenClosed)

        val advancesAfterClose = handle.animationAdvances.size
        instance.tick(0)
        assertEquals(advancesAfterClose, handle.animationAdvances.size)
        assertSame(close, runtime.close().toCompletableFuture())
    }

    private fun request(
        instance: Instance,
        renderer: RecordingRenderer,
        actions: RecordingActions,
    ) =
        SceneRuntimeRequest(
            scene = scene(),
            assets = assets(),
            actions = actionCatalog(),
            identity = SceneRuntimeIdentity(SceneId("test:runtime"), "test:map", 1),
            instance = instance,
            renderers = renderer,
            effects = NoEffects,
            playerPolicy = AllowAllPlayers,
            actionRegistry = actions,
            clock = SceneClock { 1_000_000_000L },
        )

    private fun scene() =
        SceneDocument(
            schemaVersion = 1,
            id = SceneId("test:runtime"),
            metadata = SceneMetadata("Runtime", null, emptySet()),
            catalogs =
                SceneCatalogReferences(
                    CatalogReference(CatalogId("test:assets"), "1"),
                    CatalogReference(CatalogId("test:actions"), "1"),
                ),
            groups = emptyList(),
            elements =
                listOf(
                    Npc(
                        id = LocalId("guide"),
                        group = null,
                        transform =
                            Transform(
                                Vec3(0.0, 0.0, 3.0),
                                EulerRotation(45.0, 0.0, 0.0),
                                Vec3(2.0, 1.0, 0.5),
                            ),
                        activation = ActivationPolicy.ALWAYS,
                        body = AssetKey("test:npc"),
                        label = null,
                        labelOffset = Vec3(0.0, 2.0, 0.0),
                        look = LookBehavior.Fixed,
                        initialAnimation = LocalId("idle"),
                        interactionBounds = LocalBounds(Vec3(0.0, 1.62, 0.0), Vec3(1.0, 2.0, 1.0)),
                        proximity = null,
                        bindings =
                            listOf(
                                TriggerBinding(
                                    SceneTrigger.RIGHT_CLICK,
                                    listOf(HandCondition(SceneHand.OFF)),
                                    cooldownMillis = 0,
                                    debounceMillis = 0,
                                    actions =
                                        listOf(
                                            ApplicationAction(ActionKey("test:right"), emptyMap())
                                        ),
                                ),
                                TriggerBinding(
                                    SceneTrigger.LEFT_CLICK,
                                    listOf(HandCondition(SceneHand.MAIN)),
                                    cooldownMillis = 0,
                                    debounceMillis = 0,
                                    actions =
                                        listOf(
                                            SetViewerScaleAction(
                                                ElementTarget(LocalId("guide"), null),
                                                2.0,
                                                0,
                                            ),
                                            ApplicationAction(ActionKey("test:left"), emptyMap()),
                                        ),
                                ),
                            ),
                    )
                ),
        )

    private fun assets() =
        AssetCatalog(
            CatalogId("test:assets"),
            "1",
            CatalogVersionRange(CatalogId("test:assets"), "1", "1"),
            mapOf(
                AssetKey("test:npc") to
                    AssetDefinition(
                        AssetKey("test:npc"),
                        AssetKind.NPC_BODY,
                        setOf(LocalId("idle")),
                        null,
                        emptyMap(),
                    )
            ),
        )

    private fun actionCatalog() =
        ActionCatalog(
            CatalogId("test:actions"),
            "1",
            listOf("test:right", "test:left").associate { value ->
                val key = ActionKey(value)
                key to ActionDefinition(key, value, "", emptyMap())
            },
        )

    private fun tickUntil(instance: Instance, condition: () -> Boolean) {
        repeat(20) {
            if (condition()) return
            instance.tick(0)
        }
        assertTrue(condition(), "condition did not become true after 20 Instance ticks")
    }

    private class RecordingRenderer(private val instance: Instance) :
        SceneAssetRendererRegistry, SceneAssetRendererFactory {
        val handle = RecordingHandle(instance)
        val completion = CompletableFuture<RenderedAssetHandle>()
        var createCalls = 0

        override fun rendererFor(asset: AssetKey, kind: AssetKind) = this

        override fun create(
            context: SceneAssetRenderContext
        ): CompletionStage<RenderedAssetHandle> {
            createCalls++
            return completion
        }
    }

    private class RecordingHandle(private val instance: Instance) : RenderedAssetHandle {
        val animationAdvances = mutableListOf<Long>()
        val clearedViewers = mutableListOf<UUID>()
        var closed = false
        var entitiesRemovedWhenClosed = false
        var failNextAnimationAdvance = false

        override fun applyTransform(transform: SceneRenderTransform) = Unit

        override fun applyViewerState(player: Player, state: SceneViewerVisualState) = Unit

        override fun clearViewerState(player: Player) {
            clearedViewers += player.uuid
        }

        override fun startAnimation(animation: LocalId, elapsedMillis: Long) = Unit

        override fun stopAnimation(animation: LocalId?) = Unit

        override fun advanceAnimation(elapsedMillis: Long) {
            if (failNextAnimationAdvance) {
                failNextAnimationAdvance = false
                throw IllegalStateException("animation failed")
            }
            animationAdvances += elapsedMillis
        }

        override fun close() {
            closed = true
            entitiesRemovedWhenClosed =
                instance.entities.none { it.entityType == EntityType.INTERACTION }
        }
    }

    private class RecordingActions : SceneActionRegistry {
        val contexts = mutableListOf<SceneActionContext>()
        val firstRightCompletion = CompletableFuture<SceneActionResult>()
        private var rightCalls = 0

        override fun handlerFor(key: ActionKey): SceneActionHandler =
            SceneActionHandler { context ->
                contexts += context
                when (key.value) {
                    "test:right" ->
                        if (rightCalls++ == 0) firstRightCompletion else CompletableFuture()
                    else -> CompletableFuture.completedFuture(SceneActionResult.Success)
                }
            }
    }

    private data object NoEffects : SceneEffectSink {
        override fun supports(asset: AssetKey, kind: AssetKind) = true

        override fun playSound(player: Player, sound: AssetKey, volume: Double, pitch: Double) =
            Unit

        override fun emitParticle(
            instance: Instance,
            particle: AssetKey,
            point: net.minestom.server.coordinate.Point,
            count: Int,
            offset: Vec3,
            speed: Double,
        ) = Unit
    }

    private data object AllowAllPlayers : ScenePlayerPolicy {
        override fun isEligible(player: Player) = true

        override fun hasPermission(player: Player, permission: String) = true
    }

    private class FakeConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootMinestom() {
            MinecraftServer.init()
        }
    }
}
