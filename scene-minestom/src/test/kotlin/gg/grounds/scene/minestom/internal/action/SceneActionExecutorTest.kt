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
import java.util.concurrent.CompletionStage
import java.util.function.BiConsumer
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
                                null,
                                emptyList(),
                                listOf(LocalId("a"), LocalId("b")),
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
        assertEquals(LogicalAnimationState(LocalId("idle"), 11L), inactive.animation)
        assertEquals(LogicalAnimationState(null, null), active.animation)
        assertEquals(listOf(LocalId("run") to 0L), handle.started)
        assertEquals(listOf<LocalId?>(LocalId("run")), handle.stopped)
        assertTrue(firstPart.started.isEmpty())
        assertEquals(listOf(LocalId("part") to 0L), secondPart.started)
        assertEquals(
            LogicalAnimationState(LocalId("part"), 11L),
            composite.animationFor(LocalId("b")),
        )
    }

    @Test
    fun `animation start timestamp comes from action execution clock`() {
        val instance = instance()
        val player = player(instance)
        val element = state(prop("prop"))
        val clock = ManualClock(123L)

        val outcome =
            executor(instance, player, mapOf(element.element.id to element), clock = clock)
                .execute(chain(player, StartAnimationAction(target("prop"), LocalId("idle"))))

        assertEquals(ChainOutcome.SUCCEEDED, outcome.await())
        assertEquals(LogicalAnimationState(LocalId("idle"), 123L), element.animation)
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
    fun `part viewer action persists and applies only to the resolved part handle`() {
        val instance = instance()
        val player = player(instance)
        val element = state(composite("composite"))
        val firstPart = RecordingHandle()
        val secondPart = RecordingHandle()
        val viewers = ViewerStateStore()

        val outcome =
            executor(
                    instance,
                    player,
                    mapOf(element.element.id to element),
                    mapOf(
                        element.element.id to
                            ActiveElement(
                                element.element.id,
                                element.generation,
                                listOf(firstPart, secondPart),
                                null,
                                null,
                                emptyList(),
                                listOf(LocalId("a"), LocalId("b")),
                            )
                    ),
                    viewers = viewers,
                )
                .execute(
                    chain(
                        player,
                        SetViewerScaleAction(
                            ElementTarget(element.element.id, LocalId("b")),
                            2.0,
                            0,
                        ),
                    )
                )

        assertEquals(ChainOutcome.SUCCEEDED, outcome.await())
        assertTrue(firstPart.viewerUpdates.isEmpty())
        assertEquals(2.0, secondPart.viewerUpdates.single().second.scaleMultiplier)
        assertEquals(
            2.0,
            viewers
                .visualState(ViewerElementKey(player.uuid, element.element.id, LocalId("b")))
                .scaleMultiplier,
        )
        assertEquals(
            1.0,
            viewers.visualState(ViewerElementKey(player.uuid, element.element.id)).scaleMultiplier,
        )
    }

    @Test
    fun `root animation clears part overrides live`() {
        val instance = instance()
        val player = player(instance)
        val element = state(composite("composite"))
        val first = RecordingHandle()
        val second = RecordingHandle()
        element.setAnimation(LocalId("b"), LogicalAnimationState(LocalId("part"), 1L))

        executor(
                instance,
                player,
                mapOf(element.element.id to element),
                mapOf(element.element.id to compositeActive(element, first, second)),
            )
            .execute(chain(player, StartAnimationAction(target("composite"), LocalId("root"))))
            .await()

        assertEquals(listOf(LocalId("root") to 0L), first.started)
        assertEquals(listOf(LocalId("root") to 0L), second.started)
        assertEquals(
            LogicalAnimationState(LocalId("root"), 11L),
            element.animationFor(LocalId("b")),
        )
    }

    @Test
    fun `root named stop mismatch does not broadcast to a matching part override`() {
        val instance = instance()
        val player = player(instance)
        val element = state(composite("composite"))
        val first = RecordingHandle()
        val second = RecordingHandle()
        element.setAnimation(null, LogicalAnimationState(LocalId("idle"), 1L))
        element.setAnimation(LocalId("b"), LogicalAnimationState(LocalId("wave"), 2L))

        val outcome =
            executor(
                    instance,
                    player,
                    mapOf(element.element.id to element),
                    mapOf(element.element.id to compositeActive(element, first, second)),
                )
                .execute(chain(player, StopAnimationAction(target("composite"), LocalId("wave"))))
                .await()

        assertEquals(ChainOutcome.SUCCEEDED, outcome)
        assertTrue(first.stopped.isEmpty())
        assertTrue(second.stopped.isEmpty())
        assertEquals(LogicalAnimationState(LocalId("idle"), 1L), element.animationFor(LocalId("a")))
        assertEquals(LogicalAnimationState(LocalId("wave"), 2L), element.animationFor(LocalId("b")))
    }

    @Test
    fun `matching root named stop stops each parts previously effective animation`() {
        val instance = instance()
        val player = player(instance)
        val element = state(composite("composite"))
        val first = RecordingHandle()
        val second = RecordingHandle()
        element.setAnimation(null, LogicalAnimationState(LocalId("idle"), 1L))
        element.setAnimation(LocalId("b"), LogicalAnimationState(LocalId("wave"), 2L))

        val outcome =
            executor(
                    instance,
                    player,
                    mapOf(element.element.id to element),
                    mapOf(element.element.id to compositeActive(element, first, second)),
                )
                .execute(chain(player, StopAnimationAction(target("composite"), LocalId("idle"))))
                .await()

        assertEquals(ChainOutcome.SUCCEEDED, outcome)
        assertEquals(listOf<LocalId?>(LocalId("idle")), first.stopped)
        assertEquals(listOf<LocalId?>(LocalId("wave")), second.stopped)
        assertEquals(LogicalAnimationState(null, null), element.animationFor(LocalId("a")))
        assertEquals(LogicalAnimationState(null, null), element.animationFor(LocalId("b")))
    }

    @Test
    fun `root scale clears only part scale overrides and retains part highlights`() {
        val instance = instance()
        val player = player(instance)
        val element = state(composite("composite"))
        val first = RecordingHandle()
        val second = RecordingHandle()
        val viewers = ViewerStateStore()
        val executor =
            executor(
                instance,
                player,
                mapOf(element.element.id to element),
                mapOf(element.element.id to compositeActive(element, first, second)),
                viewers = viewers,
            )

        executor
            .execute(
                chain(
                    player,
                    SetViewerScaleAction(ElementTarget(element.element.id, LocalId("b")), 2.0, 0),
                    SetViewerHighlightAction(
                        ElementTarget(element.element.id, LocalId("b")),
                        true,
                        0,
                    ),
                    SetViewerScaleAction(target("composite"), 3.0, 0),
                )
            )
            .await()

        assertEquals(
            3.0,
            viewers
                .visualState(ViewerElementKey(player.uuid, element.element.id, LocalId("b")))
                .scaleMultiplier,
        )
        assertTrue(
            viewers
                .visualState(ViewerElementKey(player.uuid, element.element.id, LocalId("b")))
                .highlighted
        )
        assertEquals(3.0, second.viewerUpdates.last().second.scaleMultiplier)
        assertTrue(second.viewerUpdates.last().second.highlighted)
    }

    @Test
    fun `root highlight clears only part highlight overrides and retains part scales`() {
        val instance = instance()
        val player = player(instance)
        val element = state(composite("composite"))
        val first = RecordingHandle()
        val second = RecordingHandle()
        val viewers = ViewerStateStore()
        val executor =
            executor(
                instance,
                player,
                mapOf(element.element.id to element),
                mapOf(element.element.id to compositeActive(element, first, second)),
                viewers = viewers,
            )

        executor
            .execute(
                chain(
                    player,
                    SetViewerScaleAction(ElementTarget(element.element.id, LocalId("b")), 2.0, 0),
                    SetViewerHighlightAction(
                        ElementTarget(element.element.id, LocalId("b")),
                        true,
                        0,
                    ),
                    SetViewerHighlightAction(target("composite"), false, 0),
                )
            )
            .await()

        assertEquals(
            2.0,
            viewers
                .visualState(ViewerElementKey(player.uuid, element.element.id, LocalId("b")))
                .scaleMultiplier,
        )
        assertFalse(
            viewers
                .visualState(ViewerElementKey(player.uuid, element.element.id, LocalId("b")))
                .highlighted
        )
        assertEquals(2.0, second.viewerUpdates.last().second.scaleMultiplier)
        assertFalse(second.viewerUpdates.last().second.highlighted)
    }

    @Test
    fun `later part action overrides root only for that part`() {
        val instance = instance()
        val player = player(instance)
        val element = state(composite("composite"))
        val first = RecordingHandle()
        val second = RecordingHandle()
        val viewers = ViewerStateStore()

        executor(
                instance,
                player,
                mapOf(element.element.id to element),
                mapOf(element.element.id to compositeActive(element, first, second)),
                viewers = viewers,
            )
            .execute(
                chain(
                    player,
                    SetViewerScaleAction(target("composite"), 3.0, 0),
                    SetViewerScaleAction(ElementTarget(element.element.id, LocalId("b")), 4.0, 0),
                )
            )
            .await()

        assertEquals(3.0, first.viewerUpdates.last().second.scaleMultiplier)
        assertEquals(4.0, second.viewerUpdates.last().second.scaleMultiplier)
        assertEquals(
            3.0,
            viewers
                .visualState(ViewerElementKey(player.uuid, element.element.id, LocalId("a")))
                .scaleMultiplier,
        )
        assertEquals(
            4.0,
            viewers
                .visualState(ViewerElementKey(player.uuid, element.element.id, LocalId("b")))
                .scaleMultiplier,
        )
    }

    @Test
    fun `root viewer action clears overrides only for its triggering player`() {
        val instance = instance()
        val first = player(instance)
        val second = player(instance)
        val element = state(composite("composite"))
        val viewers = ViewerStateStore()
        val secondPart = ViewerElementKey(second.uuid, element.element.id, LocalId("b"))
        viewers.setScale(secondPart, 2.0, 0)

        executor(instance, first, mapOf(element.element.id to element), viewers = viewers)
            .execute(chain(first, SetViewerScaleAction(target("composite"), 3.0, 0)))
            .await()

        assertEquals(2.0, viewers.visualState(secondPart).scaleMultiplier)
    }

    @Test
    fun `nonzero viewer transitions expose start duration target and clock driven current state`() {
        val instance = instance()
        val player = player(instance)
        val element = state(prop("prop"))
        val clock = ManualClock(1_000_000_000L)
        val viewers = ViewerStateStore(clock)

        executor(
                instance,
                player,
                mapOf(element.element.id to element),
                viewers = viewers,
                clock = clock,
            )
            .execute(
                chain(
                    player,
                    SetViewerScaleAction(target("prop"), 3.0, 1_000),
                    SetViewerHighlightAction(target("prop"), true, 1_000),
                )
            )
            .await()

        val key = ViewerElementKey(player.uuid, element.element.id)
        val started = viewers.visualState(key)
        val scaleTransition = assertNotNull(started.scaleTransition)
        val highlightTransition = assertNotNull(started.highlightTransition)
        assertEquals(1.0, started.scaleMultiplier)
        assertFalse(started.highlighted)
        assertEquals(1_000_000_000L, scaleTransition.startedNanos)
        assertEquals(1_000_000_000L, scaleTransition.durationNanos)
        assertEquals(3.0, scaleTransition.target)
        assertEquals(1.0, scaleTransition.current)
        assertEquals(true, highlightTransition.target)
        assertEquals(false, highlightTransition.current)

        clock.advanceMillis(500)
        val halfway = viewers.visualState(key)
        assertEquals(2.0, halfway.scaleMultiplier)
        assertFalse(halfway.highlighted)
        assertEquals(2.0, halfway.scaleTransition!!.current)

        clock.advanceMillis(500)
        val completed = viewers.visualState(key)
        assertEquals(3.0, completed.scaleMultiplier)
        assertTrue(completed.highlighted)
        assertEquals(3.0, completed.scaleTransition!!.current)
        assertEquals(true, completed.highlightTransition!!.current)
        assertEquals(1, viewers.activeTransitionCount())
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
    fun `particle target uses active generation runtime rotation for composite part geometry`() {
        val instance = instance()
        val player = player(instance)
        val authored = composite("composite")
        val element =
            state(
                CompositeProp(
                    authored.id,
                    authored.group,
                    authored.transform,
                    authored.visible,
                    authored.activation,
                    authored.parts.map { part ->
                        if (part.id == LocalId("b")) {
                            part.copy(
                                transform = part.transform.copy(position = Vec3(2.0, 0.0, 0.0))
                            )
                        } else {
                            part
                        }
                    },
                )
            )
        val active = ActiveElement(element.element.id, element.generation, emptyList(), null)
        active.updateRuntimeTransform(
            SceneRenderTransform(
                element.element.transform.copy(rotation = EulerRotation(-90.0, 0.0, 0.0)),
                null,
            )
        )
        val effects = RecordingEffects()

        val outcome =
            executor(
                    instance,
                    player,
                    mapOf(element.element.id to element),
                    mapOf(element.element.id to active),
                    effects = effects,
                )
                .execute(
                    chain(
                        player,
                        EmitParticleAction(
                            ElementTarget(element.element.id, LocalId("b")),
                            AssetKey("test:particle"),
                            1,
                            Vec3(0.0, 0.0, 0.0),
                            0.0,
                        ),
                    )
                )

        assertEquals(ChainOutcome.SUCCEEDED, outcome.await())
        assertEquals(0.0, effects.particles.single().point.x(), 0.000_001)
        assertEquals(2.0, effects.particles.single().point.z(), 0.000_001)
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
        var elementGeneration = 1L
        val effects = RecordingEffects()
        val executor =
            executor(
                instance,
                player,
                emptyMap(),
                effects = effects,
                actions = SceneActionRegistry { SceneActionHandler { first } },
                isCurrent = { pending -> pending.generation == elementGeneration },
            )

        val outcome =
            executor.execute(
                chain(player, application(), PlaySoundAction(AssetKey("test:sound"), 1.0, 1.0))
            )
        elementGeneration++
        first.complete(SceneActionResult.Success)
        tick(instance)

        assertEquals(ChainOutcome.STALE, outcome.await())
        assertTrue(effects.sounds.isEmpty())
    }

    @Test
    fun `player disappearance after an action starts is stale with no later side effect`() {
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
        player.remove()
        first.complete(SceneActionResult.Success)
        tick(instance)

        assertEquals(ChainOutcome.STALE, outcome.await())
        assertTrue(effects.sounds.isEmpty())
    }

    @Test
    fun `synchronous handler registration and scheduler failures resolve returned completion`() {
        val instance = instance()
        val player = player(instance)
        val throwingHandler =
            executor(
                instance,
                player,
                emptyMap(),
                actions =
                    SceneActionRegistry {
                        SceneActionHandler { throw IllegalStateException("handler") }
                    },
            )
        val registrationFailure =
            executor(
                instance,
                player,
                emptyMap(),
                actions =
                    SceneActionRegistry {
                        SceneActionHandler {
                            CompletableFuture.completedFuture(SceneActionResult.Success)
                        }
                    },
                registerCompletion = { _, _ -> throw IllegalStateException("registration") },
            )
        var currentChecks = 0
        val schedulerFailure =
            executor(
                instance,
                player,
                emptyMap(),
                actions =
                    SceneActionRegistry {
                        SceneActionHandler {
                            CompletableFuture.completedFuture(SceneActionResult.Success)
                        }
                    },
                schedule = { throw IllegalStateException("scheduler") },
                isCurrent = {
                    currentChecks++
                    true
                },
            )

        assertEquals(
            ChainOutcome.FAILED,
            throwingHandler.execute(chain(player, application())).await(),
        )
        assertEquals(
            ChainOutcome.FAILED,
            registrationFailure.execute(chain(player, application())).await(),
        )
        assertEquals(
            ChainOutcome.FAILED,
            schedulerFailure.execute(chain(player, application())).await(),
        )
        assertEquals(2, currentChecks)
    }

    @Test
    fun `effect and application failures report safe diagnostics and causes`() {
        val instance = instance()
        val player = player(instance)
        val diagnostics = mutableListOf<SceneActionDiagnostic>()
        val effectCause = IllegalStateException("effect cause")
        val handlerCause = IllegalArgumentException("handler cause")
        val exceptionalCause = UnsupportedOperationException("stage cause")
        val throwingEffects =
            object : RecordingEffects() {
                override fun playSound(
                    player: Player,
                    sound: AssetKey,
                    volume: Double,
                    pitch: Double,
                ) {
                    throw effectCause
                }
            }
        val results =
            ArrayDeque<CompletionStage<SceneActionResult>>().apply {
                add(
                    CompletableFuture.completedFuture(
                        SceneActionResult.Failure("safe handler diagnostic", handlerCause)
                    )
                )
                add(
                    CompletableFuture<SceneActionResult>().also {
                        it.completeExceptionally(exceptionalCause)
                    }
                )
            }
        val actions = SceneActionRegistry { SceneActionHandler { results.removeFirst() } }
        val executor =
            executor(
                instance,
                player,
                emptyMap(),
                effects = throwingEffects,
                actions = actions,
                reportDiagnostic = diagnostics::add,
            )

        assertEquals(
            ChainOutcome.FAILED,
            executor
                .execute(chain(player, PlaySoundAction(AssetKey("test:sound"), 1.0, 1.0)))
                .await(),
        )
        assertEquals(
            ChainOutcome.FAILED,
            executor.execute(chain(player, application())).also { tick(instance) }.await(),
        )
        assertEquals(
            ChainOutcome.FAILED,
            executor.execute(chain(player, application())).also { tick(instance) }.await(),
        )

        assertEquals(3, diagnostics.size)
        assertSame(effectCause, diagnostics[0].cause)
        assertEquals("safe handler diagnostic", diagnostics[1].diagnostic)
        assertSame(handlerCause, diagnostics[1].cause)
        assertSame(exceptionalCause, diagnostics[2].cause)
    }

    @Test
    fun `application scheduler rejection reports the original cause without current-state read`() {
        val instance = instance()
        val player = player(instance)
        val schedulerFailure = IllegalStateException("application callback scheduler rejected")
        val diagnostics = mutableListOf<SceneActionDiagnostic>()
        val applicationCompletion = CompletableFuture<SceneActionResult>()
        var currentChecks = 0
        val executor =
            executor(
                instance,
                player,
                emptyMap(),
                actions = SceneActionRegistry { SceneActionHandler { applicationCompletion } },
                isCurrent = {
                    currentChecks++
                    true
                },
                schedule = { throw schedulerFailure },
                reportFallbackDiagnostic = diagnostics::add,
            )

        val completion = executor.execute(chain(player, application()))
        val checksBeforeCallback = currentChecks
        applicationCompletion.complete(SceneActionResult.Success)

        assertEquals(ChainOutcome.FAILED, completion.await())
        assertEquals(checksBeforeCallback, currentChecks)
        val diagnostic = assertEquals(1, diagnostics.size).let { diagnostics.single() }
        assertEquals("APPLICATION_CALLBACK_SCHEDULE_FAILED", diagnostic.code)
        assertEquals(ChainOutcome.FAILED, diagnostic.outcome)
        assertEquals("Application action callback could not be scheduled.", diagnostic.diagnostic)
        assertSame(schedulerFailure, diagnostic.cause)
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
        schedule: (Runnable) -> Unit = { instance.scheduler().execute(it) },
        registerCompletion:
            (
                CompletionStage<SceneActionResult>, BiConsumer<SceneActionResult?, Throwable?>,
            ) -> Unit =
            { stage, callback ->
                stage.whenComplete(callback)
            },
        clock: SceneClock = ManualClock(11L),
        reportDiagnostic: (SceneActionDiagnostic) -> Unit = {},
        reportFallbackDiagnostic: (SceneActionDiagnostic) -> Unit = reportDiagnostic,
    ) =
        SceneActionExecutor(
            instance,
            SceneRuntimeIdentity(SceneId("test:scene"), "map", 1),
            elements,
            active,
            viewers,
            effects,
            actions.handlerFor(ActionKey("test:action"))?.let {
                mapOf(ActionKey("test:action") to it)
            } ?: emptyMap(),
            isCurrent,
            schedule,
            registerCompletion,
            clock,
            reportDiagnostic,
            reportFallbackDiagnostic,
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

    private fun compositeActive(
        state: LogicalElementState,
        first: RecordingHandle,
        second: RecordingHandle,
    ) =
        ActiveElement(
            state.element.id,
            state.generation,
            listOf(first, second),
            null,
            null,
            emptyList(),
            listOf(LocalId("a"), LocalId("b")),
        )

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

    private open class RecordingEffects : SceneEffectSink {
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

    private class ManualClock(private var now: Long) : SceneClock {
        override fun nanoTime(): Long = now

        fun advanceMillis(millis: Long) {
            now += millis * 1_000_000L
        }
    }
}
