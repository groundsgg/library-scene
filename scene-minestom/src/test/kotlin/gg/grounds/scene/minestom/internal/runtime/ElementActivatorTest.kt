package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
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
            assertEquals(2.0f, interaction.width)
            assertEquals(3.0f, interaction.height)
            assertTrue(interaction.response)
        }
        assertEquals(state.element.id, activator.interactionElementId(entities.interaction.uuid))
        activator.deactivate(active)
        assertNull(activator.interactionElementId(entities.interaction.uuid))
        assertTrue(entities.interaction.isRemoved)
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
    fun `closes a handle that completes after its activation generation becomes stale`() {
        val factory = RecordingFactory()
        val state = state(prop())
        val activation = activator(factory).activate(state)

        state.generation++
        factory.complete(0)

        assertFailsWith<Exception> { activation.await() }
        assertTrueEventually { factory.handles.single().closed }
    }

    private fun activator(factory: RecordingFactory): ElementActivator =
        ElementActivator(
            instance(),
            SceneAssetRendererRegistry { _, _ -> factory },
            TestClock(5_000_000L),
        )

    private fun instance(): Instance {
        MinecraftServer.init()
        return MinecraftServer.getInstanceManager().createInstanceContainer()
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

        override fun create(
            context: SceneAssetRenderContext
        ): CompletableFuture<RenderedAssetHandle> {
            contexts += context
            return CompletableFuture<RenderedAssetHandle>().also(futures::add)
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

    private class RecordingHandle(val asset: String) : RenderedAssetHandle {
        var closed = false
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
        }
    }
}
