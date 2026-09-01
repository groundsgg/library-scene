package gg.grounds.scene.minestom.internal.action

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import gg.grounds.scene.minestom.internal.geometry.affine
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import gg.grounds.scene.minestom.internal.runtime.LogicalAnimationState
import gg.grounds.scene.minestom.internal.runtime.LogicalElementState
import gg.grounds.scene.minestom.internal.trigger.PendingActionChain
import gg.grounds.scene.minestom.internal.view.ViewerElementKey
import gg.grounds.scene.minestom.internal.view.ViewerStateStore
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.BiConsumer
import net.kyori.adventure.title.Title
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance

internal enum class ChainOutcome {
    SUCCEEDED,
    REJECTED,
    FAILED,
    STALE,
}

internal class SceneActionExecutor(
    private val instance: Instance,
    private val identity: SceneRuntimeIdentity,
    private val elements: Map<LocalId, LogicalElementState>,
    private val activeElements: Map<LocalId, ActiveElement> = emptyMap(),
    private val viewers: ViewerStateStore,
    private val effects: SceneEffectSink,
    private val actions: SceneActionRegistry,
    private val isCurrent: (PendingActionChain) -> Boolean,
    private val schedule: (Runnable) -> Unit = { instance.scheduler().execute(it) },
    private val registerCompletion:
        (CompletionStage<SceneActionResult>, BiConsumer<SceneActionResult?, Throwable?>) -> Unit =
        { stage, callback ->
            stage.whenComplete(callback)
        },
) {
    private val targets = ActionTargetResolver(elements, activeElements)

    fun execute(chain: PendingActionChain): CompletionStage<ChainOutcome> {
        val completion = CompletableFuture<ChainOutcome>()
        when (current(chain)) {
            true -> runNext(chain, 0, completion)
            false -> completion.complete(ChainOutcome.STALE)
            null -> completion.complete(ChainOutcome.FAILED)
        }
        return completion
    }

    private fun runNext(
        chain: PendingActionChain,
        actionIndex: Int,
        completion: CompletableFuture<ChainOutcome>,
    ) {
        if (completion.isDone) return
        when (current(chain)) {
            false -> {
                completion.complete(ChainOutcome.STALE)
                return
            }
            null -> {
                completion.complete(ChainOutcome.FAILED)
                return
            }
            true -> Unit
        }
        val action = chain.actions.getOrNull(actionIndex)
        if (action == null) {
            completion.complete(ChainOutcome.SUCCEEDED)
            return
        }
        if (action is ApplicationAction) executeApplication(chain, actionIndex, action, completion)
        else {
            val outcome =
                runCatching { executeSafe(chain, action) }.getOrElse { ChainOutcome.FAILED }
            if (outcome != null) completion.complete(outcome)
            else runNext(chain, actionIndex + 1, completion)
        }
    }

    private fun executeApplication(
        chain: PendingActionChain,
        actionIndex: Int,
        action: ApplicationAction,
        completion: CompletableFuture<ChainOutcome>,
    ) {
        val player = instance.getPlayerByUuid(chain.input.playerId)
        if (player == null || player.instance !== instance) {
            completion.complete(ChainOutcome.STALE)
            return
        }
        val context =
            SceneActionContext(
                identity,
                player,
                chain.input.npcId,
                chain.input.trigger,
                chain.input.hand,
                chain.input.acceptedNanos,
                Collections.unmodifiableMap(LinkedHashMap(action.arguments)),
                viewers.visualState(ViewerElementKey(player.uuid, chain.input.npcId)),
            )
        val stage =
            try {
                actions.handlerFor(action.key)?.execute(context)
                    ?: throw IllegalStateException("No handler for ${action.key.value}.")
            } catch (_: Throwable) {
                completeFailureOrStale(chain, completion)
                return
            }
        try {
            registerCompletion(
                stage,
                BiConsumer { result, failure ->
                    try {
                        schedule(
                            Runnable {
                                if (completion.isDone) return@Runnable
                                when (current(chain)) {
                                    false -> {
                                        completion.complete(ChainOutcome.STALE)
                                        return@Runnable
                                    }
                                    null -> {
                                        completion.complete(ChainOutcome.FAILED)
                                        return@Runnable
                                    }
                                    true -> Unit
                                }
                                when {
                                    failure != null -> completion.complete(ChainOutcome.FAILED)
                                    result is SceneActionResult.Success ->
                                        runNext(chain, actionIndex + 1, completion)
                                    result is SceneActionResult.Rejected ->
                                        completion.complete(ChainOutcome.REJECTED)
                                    else -> completion.complete(ChainOutcome.FAILED)
                                }
                            }
                        )
                    } catch (_: Throwable) {
                        completeFailureOrStale(chain, completion)
                    }
                },
            )
        } catch (_: Throwable) {
            completeFailureOrStale(chain, completion)
        }
    }

    private fun executeSafe(chain: PendingActionChain, action: SceneAction): ChainOutcome? {
        val player = instance.getPlayerByUuid(chain.input.playerId)
        if (player == null || player.instance !== instance) return ChainOutcome.STALE
        when (action) {
            is StartAnimationAction -> {
                val target = requireTarget(action.target)
                target.state.animation =
                    LogicalAnimationState(action.animation, chain.input.acceptedNanos)
                target.activeHandles.forEach { it.startAnimation(action.animation, 0) }
            }
            is StopAnimationAction -> {
                val target = requireTarget(action.target)
                if (
                    action.animation == null || target.state.animation.animation == action.animation
                )
                    target.state.animation = LogicalAnimationState(null, null)
                target.activeHandles.forEach { it.stopAnimation(action.animation) }
            }
            is SetViewerScaleAction -> {
                val target = requireTarget(action.target)
                val key = ViewerElementKey(player.uuid, target.state.element.id)
                val state = viewers.setScale(key, action.multiplier)
                target.activeHandles.forEach { it.applyViewerState(player, state) }
            }
            is SetViewerHighlightAction -> {
                val target = requireTarget(action.target)
                val key = ViewerElementKey(player.uuid, target.state.element.id)
                val state = viewers.setHighlight(key, action.enabled)
                target.activeHandles.forEach { it.applyViewerState(player, state) }
            }
            is PlaySoundAction ->
                effects.playSound(player, action.sound, action.volume, action.pitch)
            is EmitParticleAction -> {
                val target = requireTarget(action.target)
                val point = target.transform.affine().transform(Vec3(0.0, 0.0, 0.0))
                effects.emitParticle(
                    instance,
                    action.particle,
                    Pos(point.x, point.y, point.z),
                    action.count,
                    action.offset,
                    action.speed,
                )
            }
            is SendMessageAction -> player.sendMessage(action.message)
            is SendActionBarAction -> player.sendActionBar(action.message)
            is ShowTitleAction ->
                player.showTitle(
                    Title.title(
                        action.title,
                        action.subtitle,
                        Title.Times.times(
                            Duration.ofMillis(action.fadeInMillis),
                            Duration.ofMillis(action.stayMillis),
                            Duration.ofMillis(action.fadeOutMillis),
                        ),
                    )
                )
            is ApplicationAction -> error("Application actions are asynchronous.")
        }
        return null
    }

    private fun completeFailureOrStale(
        chain: PendingActionChain,
        completion: CompletableFuture<ChainOutcome>,
    ) {
        completion.complete(
            if (current(chain) == false) ChainOutcome.STALE else ChainOutcome.FAILED
        )
    }

    private fun current(chain: PendingActionChain): Boolean? =
        runCatching { isCurrent(chain) }.getOrNull()

    private fun requireTarget(target: ElementTarget): ResolvedActionTarget =
        targets.resolve(target)
            ?: throw IllegalArgumentException("Invalid action target ${target.element.value}.")
}
