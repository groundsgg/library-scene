package gg.grounds.scene.npc.minestom

import gg.grounds.npc.core.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.concurrent.TimeUnit
import kotlin.test.*
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.EntityType
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SceneNpcRenderersTest {
    @Test
    fun `mapper preserves XYZ rotation order and root plus local transforms`() {
        val root =
            Transform(Vec3(1.0, 2.0, 3.0), EulerRotation(30.0, 10.0, 20.0), Vec3(1.0, 2.0, 3.0))
        val mapped = SceneNpcTransformMapper.toNpc(SceneRenderTransform(root, root))
        assertEquals(NpcVector3(10.0, 30.0, 20.0), mapped.root.rotationDegrees)
        assertEquals(mapped.root, mapped.local)
        assertEquals(NpcVector3(1.0, 2.0, 3.0), mapped.root.position)
    }

    @Test
    fun `registry preserves exact model and animation capability and scoped lifetime ownership`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.loadChunk(0, 0).join()
        val registry =
            SceneNpcRenderers(SceneNpcModelSource { if (it == "test:bot") model() else null })
        assertNull(registry.rendererFor(AssetKey("test:bot"), AssetKind.PROP))
        assertNull(registry.rendererFor(AssetKey("test:missing"), AssetKind.NPC_BODY))
        assertTrue(registry.supportsAnimation(AssetKey("test:bot"), LocalId("idle")))
        assertFalse(registry.supportsAnimation(AssetKey("test:bot"), LocalId("missing")))
        val factory =
            assertIs<ScopedSceneAssetRendererFactory>(
                registry.rendererFor(AssetKey("test:bot"), AssetKind.NPC_BODY)
            )
        val scope = TestLifetime()
        val context =
            SceneAssetRenderContext(
                instance,
                LocalId("bot"),
                null,
                AssetKey("test:bot"),
                SceneRenderTransform(
                    Transform(
                        Vec3(0.0, 0.0, 0.0),
                        EulerRotation(0.0, 0.0, 0.0),
                        Vec3(1.0, 1.0, 1.0),
                    ),
                    null,
                ),
            )
        val created = factory.createScoped(context, scope).toCompletableFuture()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!created.isDone && System.nanoTime() < deadline) {
            instance.tick(0)
            Thread.yield()
        }
        val handle = assertIs<AsyncRenderedAssetHandle>(created.get(1, TimeUnit.SECONDS))
        assertEquals(1, scope.cleanups.size)
        assertFalse(scope.cleanups.single().toCompletableFuture().isDone)
        assertEquals(1, instance.entities.count { it.entityType == EntityType.ITEM_DISPLAY })
        scope.invalidate()
        handle.closeAsync().toCompletableFuture().get(1, TimeUnit.SECONDS)
        assertTrue(instance.entities.none { it.entityType == EntityType.ITEM_DISPLAY })
        assertEquals(0, scope.callbacks.size)
        scope.cleanups.single().toCompletableFuture().get(1, TimeUnit.SECONDS)
    }

    private fun model() =
        NpcModel(
            "test:bot",
            NpcBounds(NpcVector3.ZERO, NpcVector3.ONE),
            emptyList(),
            listOf(NpcPart(null, "test:cube", NpcVector3.ZERO, NpcVector3.ZERO, NpcVector3.ZERO)),
            mapOf("idle" to NpcClip("loop", 1.0, emptyMap())),
            emptyMap(),
        )

    private class TestLifetime : SceneRendererLifetime {
        var valid = true
        val callbacks = mutableListOf<Runnable>()

        val cleanups = mutableListOf<java.util.concurrent.CompletionStage<Void>>()

        override fun trackCleanup(cleanup: java.util.concurrent.CompletionStage<Void>) {
            cleanups += cleanup
        }

        override fun isCurrent() = valid

        override fun runIfCurrent(operation: Runnable): Boolean {
            if (!valid) return false
            operation.run()
            return true
        }

        override fun onInvalidated(callback: Runnable): AutoCloseable {
            callbacks += callback
            return AutoCloseable { callbacks -= callback }
        }

        override fun reportFailure(failure: Throwable) {
            throw AssertionError(failure)
        }

        fun invalidate() {
            valid = false
            callbacks.toList().forEach(Runnable::run)
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun boot() {
            MinecraftServer.init()
        }
    }
}
