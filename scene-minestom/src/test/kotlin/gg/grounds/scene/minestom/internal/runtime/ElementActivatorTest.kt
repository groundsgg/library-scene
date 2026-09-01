package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import gg.grounds.scene.minestom.internal.RendererCapabilityKey
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.server.entity.metadata.other.InteractionMeta
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.world.DimensionType

class ElementActivatorTest {
    @Test
    fun `activates a prop with its root transform and resumes its logical animation`() {
        val factory = RecordingFactory()
        val state =
            state(
                Prop(
                    LocalId("prop"),
                    null,
                    transform(position = Vec3(4.0, 5.0, 6.0)),
                    asset = AssetKey("test:prop"),
                    initialAnimation = null,
                ),
                animation = LogicalAnimationState(LocalId("idle"), 2_000_000L),
            )

        val activation = activator(factory).activate(state)

        assertEquals(1, factory.contexts.size)
        assertEquals(
            SceneRenderTransform(state.element.transform, null),
            factory.contexts.single().transform,
        )
        factory.complete(0)
        val active = activation.await()

        assertEquals(listOf(factory.handles.single()), active.handles)
        assertEquals(listOf(LocalId("idle") to 3L), factory.handles.single().startedAnimations)
    }

    @Test
    fun `activates composite parts in part id order and preserves root plus local transforms`() {
        val factory = RecordingFactory()
        val root = transform(position = Vec3(4.0, 0.0, 0.0))
        val first =
            CompositePart(
                LocalId("a"),
                AssetKey("test:a"),
                transform(position = Vec3(1.0, 0.0, 0.0)),
            )
        val second =
            CompositePart(
                LocalId("b"),
                AssetKey("test:b"),
                transform(position = Vec3(2.0, 0.0, 0.0)),
            )
        val state =
            state(CompositeProp(LocalId("composite"), null, root, parts = listOf(second, first)))

        val activation = activator(factory).activate(state)

        assertEquals(listOf(LocalId("a"), LocalId("b")), factory.contexts.map { it.partId })
        assertEquals(
            listOf(
                SceneRenderTransform(root, first.transform),
                SceneRenderTransform(root, second.transform),
            ),
            factory.contexts.map { it.transform },
        )
        factory.completeAll()

        assertEquals(
            listOf("test:a", "test:b"),
            activation.await().handles.map { (it as RecordingHandle).asset },
        )
    }

    @Test
    fun `reactivation reapplies part animation only to its resolved handle`() {
        val factory = RecordingFactory()
        val state = state(composite("a", "b"))
        state.setAnimation(LocalId("b"), LogicalAnimationState(LocalId("wave"), 2_000_000L))

        val activation = activator(factory).activate(state)
        factory.completeAll()
        activation.await()

        assertTrue(factory.handles[0].startedAnimations.isEmpty())
        assertEquals(listOf(LocalId("wave") to 3L), factory.handles[1].startedAnimations)
    }

    @Test
    fun `activates npc body plus runtime owned label and interaction entities`() {
        val factory = RecordingFactory()
        val state =
            state(
                Npc(
                    LocalId("npc"),
                    null,
                    transform(position = Vec3(8.0, 0.0, 0.0)),
                    body = AssetKey("test:npc"),
                    label = Component.text("Guide"),
                    labelOffset = Vec3(0.0, 2.0, 0.0),
                    look = LookBehavior.Fixed,
                    initialAnimation = null,
                    interactionBounds = LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(2.0, 3.0, 4.0)),
                    proximity = null,
                    bindings = emptyList(),
                )
            )
        val activator = activator(factory)

        val activation = activator.activate(state)
        factory.complete(0)
        val active = activation.await()

        val entities = assertNotNull(active.npcEntities)
        val label = assertNotNull(entities.label)
        assertEquals(Component.text("Guide"), (label.entityMeta as TextDisplayMeta).text)
        (entities.interaction.entityMeta as InteractionMeta).also { interaction ->
            assertEquals(4.0f, interaction.width)
            assertEquals(3.0f, interaction.height)
            assertTrue(interaction.response)
        }
        assertEquals(-0.5, entities.interaction.position.y())
        assertEquals(state.element.id, activator.interactionElementId(entities.interaction.uuid))
        activator.deactivate(active)
        assertNull(activator.interactionElementId(entities.interaction.uuid))
        assertTrue(entities.interaction.isRemoved)
    }

    @Test
    fun `keeps npc platform entities in an activation with an already completed renderer future`() {
        val factory = RecordingFactory().apply { completeImmediately = true }

        val active = activator(factory).activate(state(npc())).await()

        assertNotNull(active.npcEntities)
        assertEquals(1, active.handles.size)
    }

    @Test
    fun `rolls back completed composite handles when a later renderer fails`() {
        val factory = RecordingFactory()
        val state =
            state(
                CompositeProp(
                    LocalId("composite"),
                    null,
                    transform(),
                    parts =
                        listOf(
                            CompositePart(LocalId("a"), AssetKey("test:a"), transform()),
                            CompositePart(LocalId("b"), AssetKey("test:b"), transform()),
                        ),
                )
            )

        val activation = activator(factory).activate(state)
        factory.complete(0)
        factory.fail(1)

        assertFailsWith<Exception> { activation.await() }
        assertTrueEventually { factory.handles.single().closed }
    }

    @Test
    fun `closes a pending renderer handle after another renderer fails`() {
        val factory = RecordingFactory()
        val state = state(composite("a", "b", "c"))

        val activation = activator(factory).activate(state)
        factory.complete(0)
        factory.fail(1)

        assertFailsWith<Exception> { activation.await() }
        factory.complete(2)

        assertTrueEventually { factory.handles.all { it.closed } }
    }

    @Test
    fun `rollback attempts every completed handle and preserves the activation failure`() {
        val factory = RecordingFactory()
        val activation = activator(factory).activate(state(composite("a", "b", "c")))
        factory.complete(0)
        factory.complete(1)
        factory.handles.last().closeFailure = IllegalStateException("close failed: test:b")

        factory.fail(2)

        assertTrue(activation.toCompletableFuture().isDone)
        val failure = assertFailsWith<ExecutionException> { activation.await() }.cause
        assertEquals("renderer failed", failure?.message)
        assertEquals(listOf("close failed: test:b"), failure?.suppressed?.map { it.message })
        assertTrue(factory.handles.all { it.closed })
    }

    @Test
    fun `active element close attempts every owned handle after a close failure`() {
        val first = RecordingHandle("test:a")
        val second =
            RecordingHandle("test:b").also {
                it.closeFailure = IllegalStateException("close failed: test:b")
            }
        val third = RecordingHandle("test:c")
        val active = ActiveElement(LocalId("composite"), 1L, listOf(first, second, third), null)

        val failure = assertFailsWith<IllegalStateException> { active.close() }

        assertEquals("close failed: test:b", failure.message)
        assertTrue(listOf(first, second, third).all { it.closed })
    }

    @Test
    fun `returns a failed stage and closes completed handles when a renderer is missing`() {
        val factory = RecordingFactory()
        val state = state(composite("a", "b"))
        val activator =
            activator(
                SceneAssetRendererRegistry { asset, _ ->
                    if (asset.value == "test:a") factory else null
                }
            )

        val activation = activator.activate(state)
        factory.complete(0)

        assertFailsWith<Exception> { activation.await() }
        assertTrueEventually { factory.handles.single().closed }
    }

    @Test
    fun `returns a failed stage and closes handles when npc attachment throws synchronously`() {
        val factory = RecordingFactory()
        val activator =
            activator(SceneAssetRendererRegistry { _, _ -> factory }, unregisteredInstance())

        val activation = activator.activate(state(npc()))

        assertFailsWith<Exception> { activation.await() }
        assertTrue(factory.contexts.isEmpty())
    }

    @Test
    fun `closes a handle that completes after its activation generation becomes stale`() {
        val factory = RecordingFactory()
        val state = state(prop())
        val activation = activator(factory).activate(state)

        state.generation++
        factory.complete(0)

        assertFailsWith<Exception> { activation.await() }
        assertTrueEventually { factory.handles.single().closed }
    }

    @Test
    fun `invisible prop and npc activate logically without renderer or platform resources`() {
        val factory = RecordingFactory()
        val activator = activator(factory)

        val prop = activator.activate(state(prop().copy(visible = false))).await()
        val authoredNpc = npc()
        val invisibleNpc =
            Npc(
                authoredNpc.id,
                authoredNpc.group,
                authoredNpc.transform,
                false,
                authoredNpc.activation,
                authoredNpc.body,
                authoredNpc.label,
                authoredNpc.labelOffset,
                authoredNpc.look,
                authoredNpc.initialAnimation,
                authoredNpc.interactionBounds,
                authoredNpc.proximity,
                authoredNpc.bindings,
            )
        val npc = activator.activate(state(invisibleNpc)).await()

        assertTrue(prop.handles.isEmpty())
        assertNull(prop.npcEntities)
        assertTrue(npc.handles.isEmpty())
        assertNull(npc.npcEntities)
        assertTrue(factory.contexts.isEmpty())
    }

    @Test
    fun `callback registration failure completes activation and closes earlier composite handle`() {
        val earlier = RecordingFactory().apply { completeImmediately = true }
        val throwingStage = throwingRegistrationStage()
        val registry = SceneAssetRendererRegistry { asset, _ ->
            when (asset.value) {
                "test:a" -> earlier
                else -> SceneAssetRendererFactory { throwingStage }
            }
        }

        val activation = activator(registry).activate(state(composite("a", "b")))

        assertFailsWith<Exception> { activation.await() }
        assertTrue(earlier.handles.single().closed)
    }

    @Test
    fun `null renderer callback cannot strand activation ownership`() {
        val future = CompletableFuture<RenderedAssetHandle>()
        val activation =
            activator(SceneAssetRendererRegistry { _, _ -> SceneAssetRendererFactory { future } })
                .activate(state(prop()))

        future.complete(null)

        assertFailsWith<Exception> { activation.await() }
        assertTrue(activation.toCompletableFuture().isDone)
    }

    private fun activator(factory: RecordingFactory): ElementActivator =
        activator(SceneAssetRendererRegistry { _, _ -> factory })

    private fun activator(renderers: SceneAssetRendererRegistry, instance: Instance = instance()) =
        ElementActivator(
            instance,
            buildMap {
                listOf("prop", "npc", "a", "b", "c", "part").forEach { id ->
                    val asset = AssetKey("test:$id")
                    val kind = if (id == "npc") AssetKind.NPC_BODY else AssetKind.PROP
                    renderers.rendererFor(asset, kind)?.let {
                        put(RendererCapabilityKey(asset, kind), it)
                    }
                }
            },
            TestClock(5_000_000L),
        )

    private fun instance(): Instance {
        MinecraftServer.init()
        return MinecraftServer.getInstanceManager().createInstanceContainer()
    }

    private fun unregisteredInstance(): Instance {
        MinecraftServer.init()
        return InstanceContainer(UUID.randomUUID(), DimensionType.OVERWORLD)
    }

    private fun state(
        element: SceneElement,
        animation: LogicalAnimationState = LogicalAnimationState(null, null),
    ) = LogicalElementState(element, animation, 1L)

    private fun prop() =
        Prop(
            LocalId("prop"),
            null,
            transform(),
            asset = AssetKey("test:prop"),
            initialAnimation = null,
        )

    private fun composite(vararg partIds: String) =
        CompositeProp(
            LocalId("composite"),
            null,
            transform(),
            parts =
                partIds.map { id -> CompositePart(LocalId(id), AssetKey("test:$id"), transform()) },
        )

    private fun npc() =
        Npc(
            LocalId("npc"),
            null,
            transform(),
            body = AssetKey("test:npc"),
            label = null,
            labelOffset = Vec3(0.0, 0.0, 0.0),
            look = LookBehavior.Fixed,
            initialAnimation = null,
            interactionBounds = LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
            proximity = null,
            bindings = emptyList(),
        )

    private fun transform(position: Vec3 = Vec3(0.0, 0.0, 0.0)) =
        Transform(position, EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))

    private fun <T> CompletionStage<T>.await(): T = toCompletableFuture().get()

    private fun assertTrueEventually(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.yield()
        }
        assertTrue(condition())
    }

    private class TestClock(private val now: Long) : SceneClock {
        override fun nanoTime(): Long = now
    }

    private class RecordingFactory : SceneAssetRendererFactory {
        val contexts = mutableListOf<SceneAssetRenderContext>()
        val futures = mutableListOf<CompletableFuture<RenderedAssetHandle>>()
        val handles = mutableListOf<RecordingHandle>()
        var completeImmediately = false

        override fun create(
            context: SceneAssetRenderContext
        ): CompletableFuture<RenderedAssetHandle> {
            contexts += context
            return CompletableFuture<RenderedAssetHandle>().also { future ->
                futures += future
                if (completeImmediately) complete(futures.lastIndex)
            }
        }

        fun complete(index: Int) {
            val handle = RecordingHandle(contexts[index].asset.value)
            handles += handle
            futures[index].complete(handle)
        }

        fun completeAll() = futures.indices.forEach(::complete)

        fun fail(index: Int) {
            futures[index].completeExceptionally(IllegalStateException("renderer failed"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun throwingRegistrationStage(): CompletionStage<RenderedAssetHandle> =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CompletionStage::class.java)) {
            _,
            method,
            _ ->
            if (method.name == "whenComplete") {
                throw IllegalStateException("registration failed")
            }
            throw UnsupportedOperationException(method.name)
        } as CompletionStage<RenderedAssetHandle>

    private class RecordingHandle(val asset: String) : RenderedAssetHandle {
        var closed = false
        var closeFailure: Throwable? = null
        val startedAnimations = mutableListOf<Pair<LocalId, Long>>()

        override fun applyTransform(transform: SceneRenderTransform) = Unit

        override fun applyViewerState(
            player: net.minestom.server.entity.Player,
            state: SceneViewerVisualState,
        ) = Unit

        override fun clearViewerState(player: net.minestom.server.entity.Player) = Unit

        override fun startAnimation(animation: LocalId, elapsedMillis: Long) {
            startedAnimations += animation to elapsedMillis
        }

        override fun stopAnimation(animation: LocalId?) = Unit

        override fun advanceAnimation(elapsedMillis: Long) = Unit

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }
}
