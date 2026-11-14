package gg.grounds.scene.npc.minestom

import gg.grounds.npc.core.*
import gg.grounds.npc.minestom.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.Test

class PersonalNpcSceneTest {
    @Test
    fun `optional capability isolates validation and is bound to the concrete scene lifetime`() {
        val fixture = Fixture()
        val a = fixture.player()
        fixture.player()
        val handle = assertIs<PersonalNpcRenderedAssetHandle>(fixture.create())
        val start = handle.preparePresentation(NpcPresentationOptions(a))
        val personal = assertIs<NpcPresentationResult.Ready>(fixture.await(start.ready())).handle
        fixture.await(personal.publish())
        assertEquals(
            2,
            fixture.instance.entities.count {
                it.entityType == net.minestom.server.entity.EntityType.ITEM_DISPLAY
            },
        )
        assertFails { fixture.await(personal.playClip("missing")) }
        assertTrue(fixture.scope.failures.isEmpty())
        val foreign = Fixture().player()
        assertIs<NpcPresentationResult.Rejected>(
            fixture.await(handle.preparePresentation(NpcPresentationOptions(foreign)).ready())
        )
        fixture.scope.invalidate()
        fixture.scope.cleanup.single().toCompletableFuture().get(1, TimeUnit.SECONDS)
        assertEquals(NpcPresentationPhase.CLOSED, personal.snapshot().phase)
        assertTrue(
            fixture.instance.entities.none {
                it.entityType == net.minestom.server.entity.EntityType.ITEM_DISPLAY
            }
        )
        assertIs<NpcPresentationResult.Rejected>(
            fixture.await(handle.preparePresentation(NpcPresentationOptions(a)).ready())
        )
    }

    @Test
    fun `scene close owns a private construction blocked in foreign mesh code`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val block = AtomicBoolean(false)
        val fixture =
            Fixture(
                NpcMeshResolver {
                    if (block.get()) {
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }
                    ItemStack.of(Material.STONE)
                }
            )
        val a = fixture.player()
        val handle = assertIs<PersonalNpcRenderedAssetHandle>(fixture.create())
        block.set(true)
        val start = handle.preparePresentation(NpcPresentationOptions(a))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val tick = executor.submit { fixture.instance.tick(0) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            fixture.scope.invalidate()
            assertFalse(fixture.scope.cleanup.single().toCompletableFuture().isDone)
            release.countDown()
            tick.get(2, TimeUnit.SECONDS)
            fixture.scope.cleanup.single().toCompletableFuture().get(1, TimeUnit.SECONDS)
            assertIs<NpcPresentationResult.Rejected>(fixture.await(start.ready()))
            assertTrue(
                fixture.instance.entities.none {
                    it.entityType == net.minestom.server.entity.EntityType.ITEM_DISPLAY
                }
            )
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `private sampling failure remains owned by the scene cleanup stage`() {
        val fixture = Fixture()
        val a = fixture.player()
        val handle = assertIs<PersonalNpcRenderedAssetHandle>(fixture.create())
        val fail = AtomicBoolean(false)
        val personal =
            assertIs<NpcPresentationResult.Ready>(
                    fixture.await(
                        handle
                            .preparePresentation(
                                NpcPresentationOptions(
                                    a,
                                    clock =
                                        NpcMonotonicClock {
                                            if (fail.get()) error("clock failed")
                                            else 10_000_000_000
                                        },
                                )
                            )
                            .ready()
                    )
                )
                .handle
        fixture.await(personal.publish())
        fixture.await(personal.playClip("idle"))
        fail.set(true)
        fixture.instance.tick(0)
        assertFails { personal.closed().toCompletableFuture().get(1, TimeUnit.SECONDS) }
        assertEquals(
            1,
            fixture.instance.entities.count {
                it.entityType == net.minestom.server.entity.EntityType.ITEM_DISPLAY
            },
        )
        fixture.scope.invalidate()
        assertFails {
            fixture.scope.cleanup.single().toCompletableFuture().get(1, TimeUnit.SECONDS)
        }
        assertTrue(
            fixture.instance.entities.none {
                it.entityType == net.minestom.server.entity.EntityType.ITEM_DISPLAY
            }
        )
    }

    private class Fixture(meshes: NpcMeshResolver? = null) {
        init {
            MinecraftServer.init()
        }

        val instance =
            MinecraftServer.getInstanceManager().createInstanceContainer().also {
                it.loadChunk(0, 0).join()
            }
        val scope = Scope()
        val registry = SceneNpcRenderers(SceneNpcModelSource { model() }, meshes)

        fun player() =
            Player(Connection(), GameProfile(UUID.randomUUID(), "test")).also {
                it.setInstance(instance, Pos.ZERO).join()
            }

        fun create(): RenderedAssetHandle {
            val factory =
                assertIs<ScopedSceneAssetRendererFactory>(
                    registry.rendererFor(AssetKey("test:bot"), AssetKind.NPC_BODY)
                )
            return await(
                factory.createScoped(
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
                    ),
                    scope,
                )
            )
        }

        fun <T> await(stage: CompletionStage<T>): T {
            val future = stage.toCompletableFuture()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!future.isDone && System.nanoTime() < deadline) {
                instance.tick(0)
                Thread.yield()
            }
            return future.get(1, TimeUnit.SECONDS)
        }
    }

    private class Connection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) {}

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    private class Scope : SceneRendererLifetime {
        var valid = true
        val callbacks = mutableListOf<Runnable>()
        val cleanup = mutableListOf<CompletionStage<Void>>()
        val failures = mutableListOf<Throwable>()

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

        override fun trackCleanup(cleanup: CompletionStage<Void>) {
            this.cleanup += cleanup
        }

        override fun reportFailure(failure: Throwable) {
            failures += failure
        }

        fun invalidate() {
            valid = false
            callbacks.toList().forEach(Runnable::run)
        }
    }

    companion object {
        private fun model() =
            NpcModel(
                "test:bot",
                NpcBounds(NpcVector3.ZERO, NpcVector3.ONE),
                emptyList(),
                listOf(
                    NpcPart(
                        null,
                        "minecraft:stone",
                        NpcVector3.ZERO,
                        NpcVector3.ZERO,
                        NpcVector3.ZERO,
                    )
                ),
                mapOf("idle" to NpcClip("loop", 1.0, emptyMap())),
                emptyMap(),
            )
    }
}
