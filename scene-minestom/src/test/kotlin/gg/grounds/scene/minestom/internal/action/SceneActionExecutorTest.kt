package gg.grounds.scene.minestom.internal.action

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import gg.grounds.scene.minestom.internal.runtime.*
import gg.grounds.scene.minestom.internal.trigger.*
import gg.grounds.scene.minestom.internal.view.ViewerElementKey
import gg.grounds.scene.minestom.internal.view.ViewerStateStore
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.play.*
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection

class SceneActionExecutorTest {
    @Test
    fun `animation updates logical state before optional active handles including inactive targets`() {
        val instance = instance()
        val player = player(instance)
        val inactive = state(prop("inactive"))
        val active = state(prop("active"))
        val composite = state(composite("composite"))
        val handle = RecordingHandle()
        val firstPart = RecordingHandle()
        val secondPart = RecordingHandle()

        val outcome =
            executor(
                    instance,
                    player,
                    mapOf(
                        inactive.element.id to inactive,
                        active.element.id to active,
                        composite.element.id to composite,
                    ),
                    mapOf(
                        active.element.id to active(active, handle),
                        composite.element.id to
                            ActiveElement(
                                composite.element.id,
                                composite.generation,
                                listOf(firstPart, secondPart),
                                null,
                            ),
                    ),
                )
                .execute(
                    chain(
                        player,
                        StartAnimationAction(target("inactive"), LocalId("idle")),
                        StartAnimationAction(target("active"), LocalId("run")),
                        StartAnimationAction(
                            ElementTarget(LocalId("composite"), LocalId("b")),
                            LocalId("part"),
                        ),
                        StopAnimationAction(target("active"), LocalId("run")),
                    )
                )

        assertEquals(ChainOutcome.SUCCEEDED, outcome.await())
        assertEquals(LogicalAnimationState(LocalId("idle"), 7L), inactive.animation)
        assertEquals(LogicalAnimationState(null, null), active.animation)
        assertEquals(listOf(LocalId("run") to 0L), handle.started)
        assertEquals(listOf<LocalId?>(LocalId("run")), handle.stopped)
        assertTrue(firstPart.started.isEmpty())
        assertEquals(listOf(LocalId("part") to 0L), secondPart.started)
    }

    @Test
    fun `viewer scale and highlight apply only to the triggering player`() {
        val instance = instance()
        val first = player(instance)
        val second = player(instance)
        val element = state(prop("prop"))
        val handle = RecordingHandle()
        val viewers = ViewerStateStore()

        val outcome =
            executor(
                    instance,
                    first,
                    mapOf(element.element.id to element),
                    mapOf(element.element.id to active(element, handle)),
                    viewers = viewers,
                )
                .execute(
                    chain(
                        first,
                        SetViewerScaleAction(target("prop"), 2.0, 0),
                        SetViewerHighlightAction(target("prop"), true, 0),
                    )
                )

        assertEquals(ChainOutcome.SUCCEEDED, outcome.await())
        assertEquals(
            SceneViewerVisualState(2.0, true),
            viewers.visualState(ViewerElementKey(first.uuid, LocalId("prop"))),
        )
        assertEquals(
            SceneViewerVisualState(),
            viewers.visualState(ViewerElementKey(second.uuid, LocalId("prop"))),
        )
        assertEquals(listOf(first.uuid, first.uuid), handle.viewerUpdates.map { it.first })
    }

    @Test
    fun `sound particle and player messages delegate without activating targets`() {
        val instance = instance()
        val player = player(instance)
        val element = state(prop("prop", position = Vec3(3.0, 4.0, 5.0)))
        val effects = RecordingEffects()

        val outcome =
            executor(instance, player, mapOf(element.element.id to element), effects = effects)
                .execute(
                    chain(
                        player,
                        PlaySoundAction(AssetKey("test:sound"), 1.0, 1.0),
                        EmitParticleAction(
                            target("prop"),
                            AssetKey("test:particle"),
                            2,
                            Vec3(1.0, 0.0, 0.0),
                            0.5,
                        ),
                        SendMessageAction(Component.text("message")),
                        SendActionBarAction(Component.text("bar")),
                        ShowTitleAction(
                            Component.text("title"),
                            Component.text("subtitle"),
                            1,
                            2,
                            3,
                        ),
                    )
                )

        assertEquals(ChainOutcome.SUCCEEDED, outcome.await())
        assertEquals(listOf(player.uuid), effects.sounds)
        assertEquals(3.0, effects.particles.single().point.x())
        assertEquals(4.0, effects.particles.single().point.y())
        assertEquals(5.0, effects.particles.single().point.z())
        assertEquals(
            listOf(
                SystemChatPacket::class,
                ActionBarPacket::class,
                SetTitleTimePacket::class,
                SetTitleSubTitlePacket::class,
                SetTitleTextPacket::class,
            ),
            player.packets.takeLast(5).map { it::class },
        )
    }

    @Test
    fun `application action returns success rejection and failure outcomes`() {
        val instance = instance()
        val player = player(instance)
        val element = state(npc("npc"))
        var invocation = 0
        val actions = SceneActionRegistry {
            SceneActionHandler {
                when (invocation++) {
                    0 -> CompletableFuture.completedFuture(SceneActionResult.Success)
                    1 -> CompletableFuture.completedFuture(SceneActionResult.Rejected("no"))
                    else ->
                        CompletableFuture<SceneActionResult>().also {
                            it.completeExceptionally(IllegalStateException("boom"))
                        }
                }
            }
        }
        val executor =
            executor(instance, player, mapOf(element.element.id to element), actions = actions)

        executor.execute(chain(player, application())).also {
            tick(instance)
            assertEquals(ChainOutcome.SUCCEEDED, it.await())
        }
        executor.execute(chain(player, application())).also {
            tick(instance)
            assertEquals(ChainOutcome.REJECTED, it.await())
        }
        executor.execute(chain(player, application())).also {
            tick(instance)
            assertEquals(ChainOutcome.FAILED, it.await())
        }
    }

    @Test
    fun `does not start second action until asynchronous first action succeeds`() {
        val instance = instance()
        val player = player(instance)
        val first = CompletableFuture<SceneActionResult>()
        val effects = RecordingEffects()
        val executor =
            executor(
                instance,
                player,
                emptyMap(),
                effects = effects,
                actions = SceneActionRegistry { SceneActionHandler { first } },
            )

        val outcome =
            executor.execute(
                chain(player, application(), PlaySoundAction(AssetKey("test:sound"), 1.0, 1.0))
            )
        assertTrue(effects.sounds.isEmpty())
        first.complete(SceneActionResult.Success)
        assertTrue(effects.sounds.isEmpty())
        tick(instance)

        assertEquals(ChainOutcome.SUCCEEDED, outcome.await())
        assertEquals(listOf(player.uuid), effects.sounds)
    }

    @Test
    fun `completion after invalidation is stale and starts no later side effect`() {
        val instance = instance()
        val player = player(instance)
        val first = CompletableFuture<SceneActionResult>()
        var current = true
        val effects = RecordingEffects()
        val executor =
            executor(
                instance,
                player,
                emptyMap(),
                effects = effects,
                actions = SceneActionRegistry { SceneActionHandler { first } },
                isCurrent = { current },
            )

        val outcome =
            executor.execute(
                chain(player, application(), PlaySoundAction(AssetKey("test:sound"), 1.0, 1.0))
            )
        current = false
        first.complete(SceneActionResult.Success)
        tick(instance)

        assertEquals(ChainOutcome.STALE, outcome.await())
        assertTrue(effects.sounds.isEmpty())
    }

    private fun executor(
        instance: Instance,
        player: Player,
        elements: Map<LocalId, LogicalElementState>,
        active: Map<LocalId, ActiveElement> = emptyMap(),
        viewers: ViewerStateStore = ViewerStateStore(),
        effects: RecordingEffects = RecordingEffects(),
        actions: SceneActionRegistry = SceneActionRegistry { null },
        isCurrent: (PendingActionChain) -> Boolean = { true },
    ) =
        SceneActionExecutor(
            instance,
            SceneRuntimeIdentity(SceneId("test:scene"), "map", 1),
            elements,
            active,
            viewers,
            effects,
            actions,
            isCurrent,
        )

    private fun chain(player: Player, vararg actions: SceneAction) =
        PendingActionChain(
            BindingKey(player.uuid, LocalId("npc"), 0),
            1,
            SceneTriggerInput(
                player.uuid,
                LocalId("npc"),
                SceneTrigger.LEFT_CLICK,
                SceneHand.MAIN,
                7L,
            ),
            actions.toList(),
        )

    private fun application() = ApplicationAction(ActionKey("test:action"), emptyMap())

    private fun target(id: String) = ElementTarget(LocalId(id), null)

    private fun state(element: SceneElement) =
        LogicalElementState(element, LogicalAnimationState(null, null), 1)

    private fun active(state: LogicalElementState, handle: RecordingHandle) =
        ActiveElement(state.element.id, state.generation, listOf(handle), null)

    private fun prop(id: String, position: Vec3 = Vec3(0.0, 0.0, 0.0)) =
        Prop(
            id = LocalId(id),
            group = null,
            transform = Transform(position, EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
            visible = true,
            activation = ActivationPolicy.ALWAYS,
            asset = AssetKey("test:prop"),
            initialAnimation = null,
        )

    private fun composite(id: String) =
        CompositeProp(
            LocalId(id),
            null,
            Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
            parts =
                listOf(
                    CompositePart(
                        LocalId("b"),
                        AssetKey("test:b"),
                        Transform(
                            Vec3(0.0, 0.0, 0.0),
                            EulerRotation(0.0, 0.0, 0.0),
                            Vec3(1.0, 1.0, 1.0),
                        ),
                    ),
                    CompositePart(
                        LocalId("a"),
                        AssetKey("test:a"),
                        Transform(
                            Vec3(0.0, 0.0, 0.0),
                            EulerRotation(0.0, 0.0, 0.0),
                            Vec3(1.0, 1.0, 1.0),
                        ),
                    ),
                ),
        )

    private fun npc(id: String) =
        Npc(
            id = LocalId(id),
            group = null,
            transform =
                Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
            visible = true,
            activation = ActivationPolicy.ALWAYS,
            body = AssetKey("test:npc"),
            label = null,
            labelOffset = Vec3(0.0, 0.0, 0.0),
            look = LookBehavior.Fixed,
            initialAnimation = null,
            interactionBounds = LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
            proximity = null,
            bindings = emptyList(),
        )

    private fun instance(): Instance {
        MinecraftServer.init()
        return MinecraftServer.getInstanceManager().createInstanceContainer()
    }

    private fun player(instance: Instance) =
        TestPlayer().also { it.setInstance(instance, Pos.ZERO).join() }

    private fun tick(instance: Instance) = instance.tick(0)

    private fun <T> java.util.concurrent.CompletionStage<T>.await(): T = toCompletableFuture().get()

    private class TestPlayer private constructor(private val connection: Connection) :
        Player(connection, GameProfile(UUID.randomUUID(), "test")) {
        constructor() : this(Connection())

        val packets
            get() = connection.packets
    }

    private class Connection : PlayerConnection() {
        val packets = mutableListOf<SendablePacket>()

        override fun sendPacket(packet: SendablePacket) {
            packets += packet
        }

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    private class RecordingHandle : RenderedAssetHandle {
        val started = mutableListOf<Pair<LocalId, Long>>()
        val stopped = mutableListOf<LocalId?>()
        val viewerUpdates = mutableListOf<Pair<UUID, SceneViewerVisualState>>()

        override fun applyTransform(transform: SceneRenderTransform) = Unit

        override fun applyViewerState(player: Player, state: SceneViewerVisualState) {
            viewerUpdates += player.uuid to state
        }

        override fun clearViewerState(player: Player) = Unit

        override fun startAnimation(animation: LocalId, elapsedMillis: Long) {
            started += animation to elapsedMillis
        }

        override fun stopAnimation(animation: LocalId?) {
            stopped += animation
        }

        override fun advanceAnimation(elapsedMillis: Long) = Unit

        override fun close() = Unit
    }

    private class RecordingEffects : SceneEffectSink {
        val sounds = mutableListOf<UUID>()

        data class Particle(val point: net.minestom.server.coordinate.Point)

        val particles = mutableListOf<Particle>()

        override fun supports(asset: AssetKey, kind: AssetKind) = true

        override fun playSound(player: Player, sound: AssetKey, volume: Double, pitch: Double) {
            sounds += player.uuid
        }

        override fun emitParticle(
            instance: Instance,
            particle: AssetKey,
            point: net.minestom.server.coordinate.Point,
            count: Int,
            offset: Vec3,
            speed: Double,
        ) {
            particles += Particle(point)
        }
    }
}
