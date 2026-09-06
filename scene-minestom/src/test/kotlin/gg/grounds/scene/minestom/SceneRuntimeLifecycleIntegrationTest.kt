package gg.grounds.scene.minestom

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.internal.DefaultSceneRuntime
import gg.grounds.scene.minestom.internal.SceneReadiness
import gg.grounds.scene.minestom.internal.SceneReadinessRequest
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.entity.EntityDespawnEvent
import net.minestom.server.event.instance.InstanceChunkLoadEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.ChunkLoader
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SceneRuntimeLifecycleIntegrationTest {
    @Test
    fun `readiness host exceptions become deterministic creation failures`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val base = request(instance, RecordingRenderer(instance), RecordingActions())

        val creation =
            SceneRuntimeFactory.create(
                    base.copy(
                        renderers =
                            SceneAssetRendererRegistry { _, _ ->
                                throw IllegalStateException("host registry failed")
                            }
                    )
                )
                .toCompletableFuture()

        assertTrue(creation.isDone)
        val problem = assertIs<SceneRuntimeCreationResult.Failure>(creation.get()).problems.single()
        assertEquals(SceneRuntimeProblemCode.RUNTIME_FAILURE, problem.code)
        assertEquals("runtime", problem.path)
        assertEquals("Runtime readiness check failed.", problem.message)
        assertEquals(emptySet(), instance.eventNode().children)
    }

    @Test
    fun `close waits for a gated automatic renderer delivery to reclaim its late handle`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
            it.setInstance(instance, Pos(0.0, 0.0, 0.0)).join()
        }
        val renderer = RecordingRenderer(instance)
        val automaticScene = automaticScene()
        val creation =
            SceneRuntimeFactory.create(
                    request(instance, renderer, RecordingActions(), scene = automaticScene)
                )
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        val runtime = assertIs<SceneRuntimeCreationResult.Success>(creation.get()).runtime

        tickUntil(instance) { renderer.createCalls == 1 }
        val interaction = instance.entities.single { it.entityType == EntityType.INTERACTION }
        val close = runtime.close().toCompletableFuture()

        instance.tick(0)
        assertFalse(close.isDone)
        assertTrue(interaction.isRemoved)

        Thread.startVirtualThread { renderer.completion.complete(renderer.handle) }.join()
        tickUntil(instance) { close.isDone }
        close.get()
        assertTrue(renderer.handle.closed)
        assertTrue(renderer.handle.entitiesRemovedWhenClosed)
    }

    @Test
    fun `close drains a real renderer entity attachment in a distant chunk`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
            it.setInstance(instance, Pos.ZERO).join()
        }
        val gate = ChunkGate(20, 20).also { it.install(instance) }
        val renderer = EntityAttachingRenderer(instance, Pos(320.0, 2.0, 320.0))
        val creation =
            SceneRuntimeFactory.create(
                    request(
                        instance,
                        renderer,
                        RecordingActions(),
                        scene = automaticPropScene(),
                        assetCatalog = propAssets(),
                    )
                )
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        val runtime = assertIs<SceneRuntimeCreationResult.Success>(creation.get()).runtime

        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        val close = runtime.close().toCompletableFuture()
        instance.tick(0)
        assertFalse(close.isDone)

        gate.close()
        gate.settled.awaitReady()
        tickUntil(instance) { close.isDone }
        close.get()

        assertTrue(renderer.handle.closed)
        assertTrue(renderer.display.isRemoved)
        assertTrue(instance.entities.none { it === renderer.display })
        val registeredAfterClose = instance.entities.toSet()
        instance.tick(0)
        assertEquals(registeredAfterClose, instance.entities.toSet())
    }

    @Test
    fun `close drains distinct chunk npc attachments before reporting cleanup`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
            it.setInstance(instance, Pos.ZERO).join()
        }
        val gate = ChunkGate(0, 21, 20, 0).also { it.install(instance) }
        val renderer = ImmediateRenderer(instance)
        val npc = scene().elements.single() as Npc
        val automaticNpc =
            Npc(
                npc.id,
                npc.group,
                Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                npc.visible,
                ActivationPolicy.AUTOMATIC,
                npc.body,
                Component.text("Guide"),
                Vec3(320.0, 2.0, 0.0),
                LookBehavior.Fixed,
                npc.initialAnimation,
                LocalBounds(Vec3(0.5, 0.0, 336.0), Vec3(1.5, 2.0, 337.0)),
                npc.proximity,
                npc.bindings,
            )
        val base = scene()
        val automaticScene =
            SceneDocument(
                base.schemaVersion,
                base.id,
                base.metadata,
                base.catalogs,
                base.groups,
                listOf(automaticNpc),
            )
        val creation =
            SceneRuntimeFactory.create(
                    request(instance, renderer, RecordingActions(), scene = automaticScene)
                )
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        val runtime = assertIs<SceneRuntimeCreationResult.Success>(creation.get()).runtime

        tickUntil(instance) { renderer.contexts.isNotEmpty() }
        assertTrue(
            gate.entered.await(5, TimeUnit.SECONDS),
            "Timed out waiting for chunk boundary; requested=${gate.requested}; " +
                "entities=${instance.entities.map { it.entityType to it.position }}",
        )
        gate.observedSettled.awaitReady()
        val label = instance.entities.single { it.entityType == EntityType.TEXT_DISPLAY }
        assertEquals(20, label.position.chunkX())
        assertTrue(gate.requested.contains(0 to 21))
        val close = runtime.close().toCompletableFuture()
        instance.tick(0)
        assertFalse(close.isDone)

        gate.close()
        gate.settled.awaitReady()
        tickUntil(instance) { close.isDone }
        close.get()

        assertTrue(
            instance.entities.none { it === label || it.entityType == EntityType.INTERACTION }
        )
        val registeredAfterClose = instance.entities.toSet()
        instance.tick(0)
        assertEquals(registeredAfterClose, instance.entities.toSet())
    }

    @Test
    fun `rejected foreign npc attachment continuation fails close without off-owner cleanup`() {
        val ownerThread = Thread.currentThread()
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
            it.setInstance(instance, Pos.ZERO).join()
        }
        val gate = ChunkGate(0, 21, 20, 0).also { it.install(instance) }
        val renderer = ImmediateRenderer(instance)
        val npc = scene().elements.single() as Npc
        val automaticNpc =
            Npc(
                npc.id,
                npc.group,
                Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                npc.visible,
                ActivationPolicy.AUTOMATIC,
                npc.body,
                Component.text("Guide"),
                Vec3(320.0, 2.0, 0.0),
                npc.look,
                npc.initialAnimation,
                LocalBounds(Vec3(0.5, 0.0, 336.0), Vec3(1.5, 2.0, 337.0)),
                npc.proximity,
                npc.bindings,
            )
        val base = scene()
        val automaticScene =
            SceneDocument(
                base.schemaVersion,
                base.id,
                base.metadata,
                base.catalogs,
                base.groups,
                listOf(automaticNpc),
            )
        val schedulerFailure = IllegalStateException("transient owner scheduler rejection")
        val rejected = AtomicBoolean()
        val failures = mutableListOf<Triple<String, Throwable, Thread>>()
        val despawnThreads = mutableListOf<Thread>()
        instance.eventNode().addListener(EntityDespawnEvent::class.java) {
            despawnThreads += Thread.currentThread()
        }
        val runtimeRequest = request(instance, renderer, RecordingActions(), scene = automaticScene)
        val creation =
            DefaultSceneRuntime.create(
                    runtimeRequest,
                    checkNotNull(readiness(runtimeRequest).capabilities),
                    schedule = { action ->
                        if (
                            Thread.currentThread() !== ownerThread &&
                                rejected.compareAndSet(false, true)
                        ) {
                            throw schedulerFailure
                        }
                        instance.scheduler().execute(action)
                    },
                    failureObserver = { code, error ->
                        failures += Triple(code, error, Thread.currentThread())
                    },
                )
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        val runtime = assertIs<SceneRuntimeCreationResult.Success>(creation.get()).runtime
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))

        val close = runtime.close().toCompletableFuture()
        instance.tick(0)
        assertFalse(close.isDone)
        gate.close()
        gate.settled.awaitReady()
        tickUntil(instance) { close.isDone }

        val closeFailure = assertFailsWith<ExecutionException> { close.get() }.cause
        assertSame(schedulerFailure, closeFailure)
        assertTrue(rejected.get())
        assertTrue(despawnThreads.all { it === ownerThread })
        assertEquals(1, failures.size)
        assertEquals("RESOURCE_DRAIN_FAILED", failures.single().first)
        assertSame(schedulerFailure, failures.single().second)
        assertSame(ownerThread, failures.single().third)
        val platformEntities =
            instance.entities.filter {
                it.entityType == EntityType.TEXT_DISPLAY || it.entityType == EntityType.INTERACTION
            }
        assertEquals(2, platformEntities.size)
        assertTrue(platformEntities.none { it.isRemoved })

        platformEntities.forEach(Entity::remove)
        assertTrue(despawnThreads.all { it === ownerThread })
    }

    @Test
    fun `close drains an active tracked npc move before reporting cleanup`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
            it.setInstance(instance, Pos(10.0, 0.0, 0.0)).join()
        }
        val renderer = ImmediateRenderer(instance)
        val clock = MutableClock()
        val initialPlatformChunks = CountDownLatch(2)
        instance.eventNode().addListener(InstanceChunkLoadEvent::class.java) {
            if ((it.chunk.chunkX == 20 || it.chunk.chunkX == 21) && it.chunk.chunkZ == 0) {
                initialPlatformChunks.countDown()
            }
        }
        val npc = scene().elements.single() as Npc
        val trackedNpc =
            Npc(
                npc.id,
                npc.group,
                Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                npc.visible,
                ActivationPolicy.ALWAYS,
                npc.body,
                Component.text("Guide"),
                Vec3(320.0, 2.0, 0.0),
                LookBehavior.TrackNearest(32.0, yawOnly = true, 180.0),
                npc.initialAnimation,
                LocalBounds(Vec3(336.0, 0.0, 0.5), Vec3(337.0, 2.0, 1.5)),
                npc.proximity,
                npc.bindings,
            )
        val base = scene()
        val trackedScene =
            SceneDocument(
                base.schemaVersion,
                base.id,
                base.metadata,
                base.catalogs,
                base.groups,
                listOf(trackedNpc),
            )
        val creation =
            SceneRuntimeFactory.create(
                    request(
                        instance,
                        renderer,
                        RecordingActions(),
                        scene = trackedScene,
                        clock = clock,
                    )
                )
                .toCompletableFuture()
        instance.tick(0)
        initialPlatformChunks.awaitReady()
        tickUntil(instance) { creation.isDone }
        val runtime = assertIs<SceneRuntimeCreationResult.Success>(creation.get()).runtime
        val label = instance.entities.single { it.entityType == EntityType.TEXT_DISPLAY }
        val interaction = instance.entities.single { it.entityType == EntityType.INTERACTION }
        assertEquals(20, label.position.chunkX())
        assertEquals(21, interaction.position.chunkX())

        instance.tick(0)
        val gate = ChunkGate(-1, 21, 0, 20).also { it.install(instance) }
        clock.advanceSeconds(1)
        instance.tick(0)
        assertTrue(
            gate.entered.await(5, TimeUnit.SECONDS),
            "Timed out waiting for tracked move; requested=${gate.requested}; " +
                "label=${label.position}; interaction=${interaction.position}",
        )
        gate.observedSettled.awaitReady()
        val close = runtime.close().toCompletableFuture()
        instance.tick(0)
        assertFalse(close.isDone)

        gate.close()
        gate.settled.awaitReady()
        tickUntil(instance) { close.isDone }
        close.get()

        assertTrue(instance.entities.none { it === label || it === interaction })
        val registeredAfterClose = instance.entities.toSet()
        instance.tick(0)
        assertEquals(registeredAfterClose, instance.entities.toSet())
    }

    @Test
    fun `automatic activation aborts immediately when its last eligible player leaves`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player =
            Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
                it.setInstance(instance, Pos.ZERO).join()
            }
        val renderer = RecordingRenderer(instance)
        val config =
            SceneRuntimeConfig(
                activationDistance = 4.0,
                deactivationDistance = 6.0,
                spatialIntervalTicks = 100,
            )
        val creation =
            SceneRuntimeFactory.create(
                    request(
                        instance,
                        renderer,
                        RecordingActions(),
                        automaticScene(
                            transform =
                                Transform(
                                    Vec3(0.0, 0.0, 0.0),
                                    EulerRotation(0.0, 0.0, 0.0),
                                    Vec3(1.0, 1.0, 1.0),
                                )
                        ),
                        config,
                    )
                )
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        val runtime = assertIs<SceneRuntimeCreationResult.Success>(creation.get()).runtime
        tickUntil(instance) { renderer.createCalls == 1 }
        val pendingInteraction =
            instance.entities.single { it.entityType == EntityType.INTERACTION }

        instance.eventNode().call(PlayerDisconnectEvent(player))
        assertTrue(pendingInteraction.isRemoved)
        Thread.startVirtualThread { renderer.completion.complete(renderer.handle) }.join()
        tickUntil(instance) { renderer.handle.closed }

        assertEquals(1, renderer.createCalls)
        runtime.close().toCompletableFuture().also { close -> tickUntil(instance) { close.isDone } }
    }

    @Test
    fun `budget queued activation is discarded after last eligible player leaves`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player =
            Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
                it.setInstance(instance, Pos.ZERO).join()
            }
        val renderer = ImmediateRenderer(instance)
        val config = SceneRuntimeConfig(transitionBudgetPerTick = 1, spatialIntervalTicks = 100)
        val creation =
            SceneRuntimeFactory.create(
                    request(instance, renderer, RecordingActions(), automaticPairScene(), config)
                )
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        val runtime = assertIs<SceneRuntimeCreationResult.Success>(creation.get()).runtime
        tickUntil(instance) { renderer.contexts.any { it.elementId == LocalId("first") } }

        instance.eventNode().call(PlayerDisconnectEvent(player))
        repeat(3) { instance.tick(0) }

        assertTrue(renderer.contexts.none { it.elementId == LocalId("target") })
        runtime.close().toCompletableFuture().also { close -> tickUntil(instance) { close.isDone } }
    }

    @Test
    fun `creation renderer rejection does not perform foreign fallback cleanup`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val renderer = RecordingRenderer(instance)
        val runtimeRequest =
            request(
                instance,
                renderer,
                RecordingActions(),
                scene = alwaysPropScene(),
                assetCatalog = propAssets(),
            )
        val readiness =
            SceneReadiness.prepare(
                SceneReadinessRequest(
                    runtimeRequest.scene,
                    runtimeRequest.assets,
                    runtimeRequest.actions,
                    runtimeRequest.renderers,
                    runtimeRequest.effects,
                    runtimeRequest.actionRegistry,
                    runtimeRequest.identity,
                    runtimeRequest.config,
                )
            )
        var rejectContinuations = false
        val creation =
            DefaultSceneRuntime.create(
                    runtimeRequest,
                    checkNotNull(readiness.capabilities),
                    schedule = { action ->
                        if (rejectContinuations)
                            throw IllegalStateException("owner scheduler rejected continuation")
                        instance.scheduler().execute(action)
                    },
                )
                .toCompletableFuture()
        tickUntil(instance) { renderer.createCalls == 1 }

        rejectContinuations = true
        Thread.startVirtualThread { renderer.completion.complete(renderer.handle) }.join()

        assertFalse(creation.isDone)
        assertFalse(renderer.handle.closed)
    }

    @Test
    fun `continuation rejection during partial always installation closes every prepared handle`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val renderer = SequencedRenderer(instance)
        val runtimeRequest =
            request(
                instance,
                renderer,
                RecordingActions(),
                scene = alwaysPropPairScene(),
                assetCatalog = propAssets(),
            )
        val readiness = readiness(runtimeRequest)
        var acceptedBeforeRejection = -1
        val creation =
            DefaultSceneRuntime.create(
                    runtimeRequest,
                    checkNotNull(readiness.capabilities),
                    schedule = { action ->
                        if (acceptedBeforeRejection == 0) {
                            acceptedBeforeRejection = -1
                            throw IllegalStateException("owner scheduler rejected continuation")
                        }
                        if (acceptedBeforeRejection > 0) acceptedBeforeRejection--
                        instance.scheduler().execute(action)
                    },
                )
                .toCompletableFuture()
        tickUntil(instance) { renderer.createCalls == 1 }
        Thread.startVirtualThread { renderer.complete(0) }.join()
        tickUntil(instance) { renderer.createCalls == 2 }

        acceptedBeforeRejection = 1
        Thread.startVirtualThread { renderer.complete(1) }.join()
        tickUntil(instance) { creation.isDone }

        assertIs<SceneRuntimeCreationResult.Failure>(creation.get())
        assertTrue(renderer.handles.all { it.closed })
    }

    @Test
    fun `automatic continuation rejection clears pending ownership and permits retry`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
            it.setInstance(instance, Pos.ZERO).join()
        }
        val renderer = SequencedRenderer(instance)
        val runtimeRequest =
            request(
                instance,
                renderer,
                RecordingActions(),
                scene = automaticPropScene(),
                config = SceneRuntimeConfig(spatialIntervalTicks = 100),
                assetCatalog = propAssets(),
            )
        val readiness = readiness(runtimeRequest)
        var acceptedBeforeRejection = -1
        val creation =
            DefaultSceneRuntime.create(
                    runtimeRequest,
                    checkNotNull(readiness.capabilities),
                    schedule = { action ->
                        if (acceptedBeforeRejection == 0) {
                            acceptedBeforeRejection = -1
                            throw IllegalStateException("owner scheduler rejected continuation")
                        }
                        if (acceptedBeforeRejection > 0) acceptedBeforeRejection--
                        instance.scheduler().execute(action)
                    },
                )
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        assertIs<SceneRuntimeCreationResult.Success>(creation.get())
        tickUntil(instance) { renderer.createCalls == 1 }

        acceptedBeforeRejection = 1
        Thread.startVirtualThread { renderer.complete(0) }.join()
        tickUntil(instance) { renderer.handles.single().closed }

        tickUntil(instance) { renderer.createCalls == 2 }
    }

    @Test
    fun `action continuation rejection reconciles on tick and permits retrigger`() {
        val ownerThread = Thread.currentThread()
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player =
            Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
                it.setInstance(instance, Pos.ZERO).join()
            }
        val renderer = ImmediateRenderer(instance)
        val actions = RetriableActions()
        val clock = ThreadCheckingClock(ownerThread)
        val policy = ThreadCheckingPolicy(ownerThread)
        val runtimeRequest =
            request(instance, renderer, actions, playerPolicy = policy, clock = clock)
        val rejectContinuations = AtomicBoolean()
        val completionThread = AtomicReference<Thread>()
        val rejectedSubmissions = AtomicInteger()
        val creation =
            DefaultSceneRuntime.create(
                    runtimeRequest,
                    checkNotNull(readiness(runtimeRequest).capabilities),
                    schedule = { action ->
                        if (
                            rejectContinuations.get() &&
                                Thread.currentThread() === completionThread.get()
                        ) {
                            rejectedSubmissions.incrementAndGet()
                            throw IllegalStateException("transient owner scheduler rejection")
                        }
                        instance.scheduler().execute(action)
                    },
                )
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        val runtime = assertIs<SceneRuntimeCreationResult.Success>(creation.get()).runtime
        val interaction = instance.entities.single { it.entityType == EntityType.INTERACTION }

        instance
            .eventNode()
            .call(PlayerEntityInteractEvent(player, interaction, PlayerHand.OFF, Vec.ZERO))
        assertEquals(1, actions.contexts.size)

        rejectContinuations.set(true)
        val foreignCompletion =
            Thread.startVirtualThread {
                completionThread.set(Thread.currentThread())
                actions.completions.single().complete(SceneActionResult.Success)
            }
        foreignCompletion.join()
        rejectContinuations.set(false)

        assertEquals(2, rejectedSubmissions.get())
        assertEquals(0, clock.offOwnerReads.get())
        assertEquals(0, policy.offOwnerReads.get())
        instance
            .eventNode()
            .call(PlayerEntityInteractEvent(player, interaction, PlayerHand.OFF, Vec.ZERO))
        assertEquals(1, actions.contexts.size)

        instance.tick(0)
        instance
            .eventNode()
            .call(PlayerEntityInteractEvent(player, interaction, PlayerHand.OFF, Vec.ZERO))

        assertEquals(2, actions.contexts.size)
        assertEquals(0, clock.offOwnerReads.get())
        assertEquals(0, policy.offOwnerReads.get())
        runtime.close().toCompletableFuture().also { close -> tickUntil(instance) { close.isDone } }
    }

    @Test
    fun `sensor publication failure closes ownership without publishing active state`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
            it.setInstance(instance, Pos.ZERO).join()
        }
        val renderer = ImmediateRenderer(instance)
        val runtimeRequest =
            request(
                instance,
                renderer,
                RecordingActions(),
                scene = automaticPropScene(),
                assetCatalog = propAssets(),
            )
        val creation =
            DefaultSceneRuntime.create(
                    runtimeRequest,
                    checkNotNull(readiness(runtimeRequest).capabilities),
                    schedule = instance.scheduler()::execute,
                    activateSensor = { throw IllegalStateException("sensor publication failed") },
                )
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        val runtime = assertIs<SceneRuntimeCreationResult.Success>(creation.get()).runtime

        tickUntil(instance) { renderer.handles.singleOrNull()?.closeCount == 1 }
        runtime.close().toCompletableFuture().also { close -> tickUntil(instance) { close.isDone } }

        assertEquals(1, renderer.handles.single().closeCount)
    }

    @Test
    fun `runtime creation scheduler rejection completes a structured failure`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val runtimeRequest =
            request(
                instance,
                ImmediateRenderer(instance),
                RecordingActions(),
                scene = automaticPropScene(),
                assetCatalog = propAssets(),
            )
        val schedulerFailure = IllegalStateException("initial scheduler rejected installation")
        val reported = mutableListOf<Pair<String, Throwable>>()

        val creation =
            DefaultSceneRuntime.create(
                    runtimeRequest,
                    checkNotNull(readiness(runtimeRequest).capabilities),
                    schedule = { throw schedulerFailure },
                    failureObserver = { code, error -> reported += code to error },
                )
                .toCompletableFuture()

        assertIs<SceneRuntimeCreationResult.Failure>(creation.get())
        assertEquals("INSTALLATION_SCHEDULE_FAILED", reported.single().first)
        assertSame(schedulerFailure, reported.single().second)
    }

    @Test
    fun `inactive npc emits no sensor trigger and keeps proximity membership for reactivation`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player =
            Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
                it.setInstance(instance, Pos(0.0, 0.0, -0.2, 0.0f, 0.0f)).join()
            }
        val renderer = ImmediateRenderer(instance)
        val actions = RecordingActions()
        val sensorBindings =
            listOf(
                TriggerBinding(
                    SceneTrigger.PROXIMITY_ENTER,
                    emptyList(),
                    0,
                    0,
                    listOf(ApplicationAction(ActionKey("test:proximity"), emptyMap())),
                ),
                TriggerBinding(
                    SceneTrigger.HOVER_LEAVE,
                    emptyList(),
                    0,
                    0,
                    listOf(ApplicationAction(ActionKey("test:hover-leave"), emptyMap())),
                ),
            )
        val sensorScene =
            automaticScene(
                transform =
                    Transform(
                        Vec3(0.0, 0.0, 0.0),
                        EulerRotation(0.0, 0.0, 0.0),
                        Vec3(1.0, 1.0, 1.0),
                    ),
                proximity = ProximitySensor(3.0, 4.0),
                bindings = sensorBindings,
            )
        val config =
            SceneRuntimeConfig(
                activationDistance = 0.5,
                deactivationDistance = 1.0,
                deactivationGraceMillis = 0,
                spatialIntervalTicks = 1,
            )
        val creation =
            SceneRuntimeFactory.create(request(instance, renderer, actions, sensorScene, config))
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        assertIs<SceneRuntimeCreationResult.Success>(creation.get())
        tickUntil(instance) { actions.contexts.any { it.trigger == SceneTrigger.PROXIMITY_ENTER } }
        assertEquals(1, actions.contexts.count { it.trigger == SceneTrigger.PROXIMITY_ENTER })

        player.teleport(Pos(0.0, 0.0, -2.0, 0.0f, 0.0f)).join()
        tickUntil(instance) { renderer.handles.single().closed }
        instance.tick(0)

        assertTrue(actions.contexts.none { it.trigger == SceneTrigger.HOVER_LEAVE })

        player.teleport(Pos(0.0, 0.0, -0.2, 0.0f, 0.0f)).join()
        tickUntil(instance) { renderer.handles.size == 2 }
        repeat(3) { instance.tick(0) }
        assertEquals(1, actions.contexts.count { it.trigger == SceneTrigger.PROXIMITY_ENTER })
    }

    @Test
    fun `policy invalidation clears logical viewer state even when renderer clear throws`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player =
            Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
                it.setInstance(instance, Pos.ZERO).join()
            }
        val policy = TogglePolicy()
        val renderer = RecordingRenderer(instance)
        val actions = RecordingActions()
        val creation =
            SceneRuntimeFactory.create(request(instance, renderer, actions, playerPolicy = policy))
                .toCompletableFuture()
        tickUntil(instance) { renderer.createCalls == 1 }
        renderer.completion.complete(renderer.handle)
        tickUntil(instance) { creation.isDone }
        assertIs<SceneRuntimeCreationResult.Success>(creation.get())
        val interaction = instance.entities.single { it.entityType == EntityType.INTERACTION }

        instance.eventNode().call(EntityAttackEvent(player, interaction))
        assertEquals(2.0, actions.contexts.last().viewerState.scaleMultiplier)
        instance.tick(0)
        renderer.handle.failNextViewerClear = true
        policy.eligible = false
        instance.tick(0)
        policy.eligible = true
        instance
            .eventNode()
            .call(PlayerEntityInteractEvent(player, interaction, PlayerHand.OFF, Vec.ZERO))

        assertEquals(1.0, actions.contexts.last().viewerState.scaleMultiplier)
        assertEquals(listOf(player.uuid), renderer.handle.clearedViewers)
    }

    @Test
    fun `viewer state reapply failure closes activation before active publication`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player =
            Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
                it.setInstance(instance, Pos.ZERO).join()
            }
        val renderer = ViewerFailingRenderer(instance)
        val config =
            SceneRuntimeConfig(
                activationDistance = 8.0,
                deactivationDistance = 10.0,
                spatialIntervalTicks = 1,
            )
        val creation =
            SceneRuntimeFactory.create(
                    request(instance, renderer, RecordingActions(), viewerReapplyScene(), config)
                )
                .toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        assertIs<SceneRuntimeCreationResult.Success>(creation.get())
        val guideInteraction = instance.entities.single { it.entityType == EntityType.INTERACTION }

        instance.eventNode().call(EntityAttackEvent(player, guideInteraction))
        player.teleport(Pos(100.0, 0.0, 0.0)).join()
        tickUntil(instance) { renderer.targetHandle?.closed == true }

        assertEquals(2, renderer.createCalls)
        assertEquals(
            listOf(guideInteraction.uuid),
            instance.entities.filter { it.entityType == EntityType.INTERACTION }.map { it.uuid },
        )
        repeat(3) { instance.tick(0) }
        assertEquals(2, renderer.createCalls)
    }

    @Test
    fun `runtime executes only the action handler captured by readiness`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player =
            Player(FakeConnection(), GameProfile(UUID.randomUUID(), "Alex")).also {
                it.setInstance(instance, Pos.ZERO).join()
            }
        val renderer = ImmediateRenderer(instance)
        val actions = OneShotActions()
        val creation =
            SceneRuntimeFactory.create(request(instance, renderer, actions)).toCompletableFuture()
        tickUntil(instance) { creation.isDone }
        assertIs<SceneRuntimeCreationResult.Success>(creation.get())
        val interaction = instance.entities.single { it.entityType == EntityType.INTERACTION }

        instance
            .eventNode()
            .call(PlayerEntityInteractEvent(player, interaction, PlayerHand.OFF, Vec.ZERO))

        assertEquals(listOf("test:right"), actions.executed)
        assertEquals(mapOf("test:left" to 1, "test:right" to 1), actions.lookups)
    }

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

        handle.failNextViewerClear = true
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
        renderer: SceneAssetRendererRegistry,
        actions: SceneActionRegistry,
        scene: SceneDocument = scene(),
        config: SceneRuntimeConfig = SceneRuntimeConfig(),
        playerPolicy: ScenePlayerPolicy = AllowAllPlayers,
        assetCatalog: AssetCatalog = assets(),
        clock: SceneClock = SceneClock { 1_000_000_000L },
    ) =
        SceneRuntimeRequest(
            scene = scene,
            assets = assetCatalog,
            actions = actionCatalog(),
            identity = SceneRuntimeIdentity(scene.id, "test:map", 1),
            instance = instance,
            renderers = renderer,
            effects = NoEffects,
            playerPolicy = playerPolicy,
            actionRegistry = actions,
            clock = clock,
            config = config,
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

    private fun automaticScene(
        transform: Transform? = null,
        proximity: ProximitySensor? = null,
        bindings: List<TriggerBinding>? = null,
    ): SceneDocument {
        val document = scene()
        val npc = document.elements.single() as Npc
        val automaticNpc =
            Npc(
                id = npc.id,
                group = npc.group,
                transform = transform ?: npc.transform,
                visible = npc.visible,
                activation = ActivationPolicy.AUTOMATIC,
                body = npc.body,
                label = npc.label,
                labelOffset = npc.labelOffset,
                look = npc.look,
                initialAnimation = npc.initialAnimation,
                interactionBounds = npc.interactionBounds,
                proximity = proximity ?: npc.proximity,
                bindings = bindings ?: npc.bindings,
            )
        return SceneDocument(
            document.schemaVersion,
            document.id,
            document.metadata,
            document.catalogs,
            document.groups,
            listOf(automaticNpc),
        )
    }

    private fun viewerReapplyScene(): SceneDocument {
        val base = scene()
        val guide = base.elements.single() as Npc
        val trigger =
            Npc(
                guide.id,
                guide.group,
                guide.transform,
                guide.visible,
                ActivationPolicy.ALWAYS,
                guide.body,
                guide.label,
                guide.labelOffset,
                guide.look,
                guide.initialAnimation,
                guide.interactionBounds,
                guide.proximity,
                listOf(
                    TriggerBinding(
                        SceneTrigger.LEFT_CLICK,
                        emptyList(),
                        0,
                        0,
                        listOf(SetViewerScaleAction(ElementTarget(LocalId("target"), null), 2.0, 0)),
                    )
                ),
            )
        val target =
            Npc(
                LocalId("target"),
                null,
                Transform(Vec3(100.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                true,
                ActivationPolicy.AUTOMATIC,
                guide.body,
                null,
                guide.labelOffset,
                LookBehavior.Fixed,
                null,
                guide.interactionBounds,
                null,
                emptyList(),
            )
        return SceneDocument(
            base.schemaVersion,
            base.id,
            base.metadata,
            base.catalogs,
            base.groups,
            listOf(trigger, target),
        )
    }

    private fun automaticPairScene(): SceneDocument {
        val base = scene()
        val authored = base.elements.single() as Npc
        fun automatic(id: String) =
            Npc(
                LocalId(id),
                null,
                Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                authored.visible,
                ActivationPolicy.AUTOMATIC,
                authored.body,
                null,
                authored.labelOffset,
                LookBehavior.Fixed,
                null,
                authored.interactionBounds,
                null,
                emptyList(),
            )
        return SceneDocument(
            base.schemaVersion,
            base.id,
            base.metadata,
            base.catalogs,
            base.groups,
            listOf(automatic("first"), automatic("target")),
        )
    }

    private fun alwaysPropScene(): SceneDocument {
        val base = scene()
        return SceneDocument(
            base.schemaVersion,
            base.id,
            base.metadata,
            base.catalogs,
            base.groups,
            listOf(
                Prop(
                    LocalId("prop"),
                    null,
                    Transform(
                        Vec3(0.0, 0.0, 0.0),
                        EulerRotation(0.0, 0.0, 0.0),
                        Vec3(1.0, 1.0, 1.0),
                    ),
                    activation = ActivationPolicy.ALWAYS,
                    asset = AssetKey("test:prop"),
                    initialAnimation = null,
                )
            ),
        )
    }

    private fun alwaysPropPairScene(): SceneDocument {
        val base = alwaysPropScene()
        val first = base.elements.single() as Prop
        return SceneDocument(
            base.schemaVersion,
            base.id,
            base.metadata,
            base.catalogs,
            base.groups,
            listOf(first.copy(id = LocalId("first")), first.copy(id = LocalId("second"))),
        )
    }

    private fun automaticPropScene(): SceneDocument {
        val base = alwaysPropScene()
        val prop = base.elements.single() as Prop
        return SceneDocument(
            base.schemaVersion,
            base.id,
            base.metadata,
            base.catalogs,
            base.groups,
            listOf(prop.copy(activation = ActivationPolicy.AUTOMATIC)),
        )
    }

    private fun readiness(request: SceneRuntimeRequest) =
        SceneReadiness.prepare(
            SceneReadinessRequest(
                request.scene,
                request.assets,
                request.actions,
                request.renderers,
                request.effects,
                request.actionRegistry,
                request.identity,
                request.config,
            )
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

    private fun propAssets() =
        AssetCatalog(
            CatalogId("test:assets"),
            "1",
            CatalogVersionRange(CatalogId("test:assets"), "1", "1"),
            mapOf(
                AssetKey("test:prop") to
                    AssetDefinition(
                        AssetKey("test:prop"),
                        AssetKind.PROP,
                        emptySet(),
                        null,
                        emptyMap(),
                    )
            ),
        )

    private fun actionCatalog() =
        ActionCatalog(
            CatalogId("test:actions"),
            "1",
            listOf("test:right", "test:left", "test:proximity", "test:hover-leave").associate {
                value ->
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

    private class EntityAttachingRenderer(
        private val instance: Instance,
        private val position: Pos,
    ) : SceneAssetRendererRegistry, SceneAssetRendererFactory {
        lateinit var display: Entity
        lateinit var handle: EntityHandle

        override fun rendererFor(asset: AssetKey, kind: AssetKind) = this

        override fun create(
            context: SceneAssetRenderContext
        ): CompletionStage<RenderedAssetHandle> {
            display = Entity(EntityType.TEXT_DISPLAY)
            return display.setInstance(instance, position).thenApply {
                EntityHandle(display).also { handle = it }
            }
        }
    }

    private class EntityHandle(private val entity: Entity) : RenderedAssetHandle {
        var closed = false

        override fun applyTransform(transform: SceneRenderTransform) = Unit

        override fun applyViewerState(player: Player, state: SceneViewerVisualState) = Unit

        override fun clearViewerState(player: Player) = Unit

        override fun startAnimation(animation: LocalId, elapsedMillis: Long) = Unit

        override fun stopAnimation(animation: LocalId?) = Unit

        override fun advanceAnimation(elapsedMillis: Long) = Unit

        override fun close() {
            closed = true
            entity.remove()
        }
    }

    private class SequencedRenderer(private val instance: Instance) :
        SceneAssetRendererRegistry, SceneAssetRendererFactory {
        val completions = mutableListOf<CompletableFuture<RenderedAssetHandle>>()
        val handles = mutableListOf<RecordingHandle>()
        var createCalls = 0

        override fun rendererFor(asset: AssetKey, kind: AssetKind) = this

        override fun create(
            context: SceneAssetRenderContext
        ): CompletionStage<RenderedAssetHandle> {
            createCalls++
            return CompletableFuture<RenderedAssetHandle>().also(completions::add)
        }

        fun complete(index: Int) {
            val handle = RecordingHandle(instance)
            handles += handle
            completions[index].complete(handle)
        }
    }

    private class ImmediateRenderer(private val instance: Instance) :
        SceneAssetRendererRegistry, SceneAssetRendererFactory {
        val handles = mutableListOf<RecordingHandle>()
        val contexts = mutableListOf<SceneAssetRenderContext>()

        override fun rendererFor(asset: AssetKey, kind: AssetKind) = this

        override fun create(
            context: SceneAssetRenderContext
        ): CompletionStage<RenderedAssetHandle> {
            contexts += context
            return CompletableFuture.completedFuture(
                RecordingHandle(instance).also { handles += it }
            )
        }
    }

    private class ViewerFailingRenderer(private val instance: Instance) :
        SceneAssetRendererRegistry, SceneAssetRendererFactory {
        var createCalls = 0
        var targetHandle: RecordingHandle? = null

        override fun rendererFor(asset: AssetKey, kind: AssetKind) = this

        override fun create(
            context: SceneAssetRenderContext
        ): CompletionStage<RenderedAssetHandle> {
            createCalls++
            val handle = RecordingHandle(instance)
            if (context.elementId == LocalId("target")) {
                handle.failViewerApply = true
                targetHandle = handle
            }
            return CompletableFuture.completedFuture(handle)
        }
    }

    private class RecordingHandle(private val instance: Instance) : RenderedAssetHandle {
        val animationAdvances = mutableListOf<Long>()
        val clearedViewers = mutableListOf<UUID>()
        var closed = false
        var closeCount = 0
        var entitiesRemovedWhenClosed = false
        var failNextAnimationAdvance = false
        var failNextViewerClear = false
        var failViewerApply = false

        override fun applyTransform(transform: SceneRenderTransform) = Unit

        override fun applyViewerState(player: Player, state: SceneViewerVisualState) {
            if (failViewerApply) throw IllegalStateException("viewer apply failed")
        }

        override fun clearViewerState(player: Player) {
            clearedViewers += player.uuid
            if (failNextViewerClear) {
                failNextViewerClear = false
                throw IllegalStateException("viewer clear failed")
            }
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
            closeCount++
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

    private class OneShotActions : SceneActionRegistry {
        val lookups = mutableMapOf<String, Int>()
        val executed = mutableListOf<String>()

        override fun handlerFor(key: ActionKey): SceneActionHandler? {
            val invocation = lookups.merge(key.value, 1, Int::plus)!!
            if (invocation != 1) return null
            return SceneActionHandler {
                executed += key.value
                CompletableFuture.completedFuture(SceneActionResult.Success)
            }
        }
    }

    private class RetriableActions : SceneActionRegistry {
        val contexts = mutableListOf<SceneActionContext>()
        val completions = mutableListOf<CompletableFuture<SceneActionResult>>()

        override fun handlerFor(key: ActionKey): SceneActionHandler =
            SceneActionHandler { context ->
                contexts += context
                CompletableFuture<SceneActionResult>().also(completions::add)
            }
    }

    private class ThreadCheckingClock(private val ownerThread: Thread) : SceneClock {
        val offOwnerReads = AtomicInteger()

        override fun nanoTime(): Long {
            if (Thread.currentThread() !== ownerThread) offOwnerReads.incrementAndGet()
            return 1_000_000_000L
        }
    }

    private class MutableClock : SceneClock {
        private var nanos = 0L

        override fun nanoTime() = nanos

        fun advanceSeconds(seconds: Long) {
            nanos += TimeUnit.SECONDS.toNanos(seconds)
        }
    }

    private class ThreadCheckingPolicy(private val ownerThread: Thread) : ScenePlayerPolicy {
        val offOwnerReads = AtomicInteger()

        override fun isEligible(player: Player): Boolean {
            if (Thread.currentThread() !== ownerThread) offOwnerReads.incrementAndGet()
            return true
        }

        override fun hasPermission(player: Player, permission: String) = true
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

    private class TogglePolicy(var eligible: Boolean = true) : ScenePlayerPolicy {
        override fun isEligible(player: Player) = eligible

        override fun hasPermission(player: Player, permission: String) = true
    }

    private class FakeConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    private class ChunkGate(
        private val chunkX: Int,
        private val chunkZ: Int,
        private val observedX: Int = chunkX,
        private val observedZ: Int = chunkZ,
    ) : AutoCloseable {
        val entered = CountDownLatch(1)
        val settled = CountDownLatch(1)
        val observedSettled = CountDownLatch(1)
        val requested = ConcurrentLinkedQueue<Pair<Int, Int>>()
        private val release = CompletableFuture<Unit>()

        fun install(instance: InstanceContainer) {
            instance.eventNode().addListener(InstanceChunkLoadEvent::class.java) {
                if (it.chunk.chunkX == chunkX && it.chunk.chunkZ == chunkZ) settled.countDown()
                if (it.chunk.chunkX == observedX && it.chunk.chunkZ == observedZ) {
                    observedSettled.countDown()
                }
            }
            instance.chunkLoader =
                object : ChunkLoader {
                    override fun supportsParallelLoading() = true

                    override fun loadChunk(instance: Instance, x: Int, z: Int): Chunk? {
                        requested += x to z
                        if (x == chunkX && z == chunkZ) {
                            entered.countDown()
                            release.get(5, TimeUnit.SECONDS)
                        }
                        return null
                    }

                    override fun saveChunk(chunk: Chunk) = Unit
                }
        }

        override fun close() {
            release.complete(Unit)
        }
    }

    private fun CountDownLatch.awaitReady() {
        assertTrue(await(5, TimeUnit.SECONDS), "Timed out waiting for chunk boundary")
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootMinestom() {
            MinecraftServer.init()
        }
    }
}
