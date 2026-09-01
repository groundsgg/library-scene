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

internal data class SceneActionDiagnostic(
    val playerId: java.util.UUID,
    val elementId: LocalId,
    val trigger: SceneTrigger,
    val bindingIndex: Int,
    val outcome: ChainOutcome,
    val code: String,
    val diagnostic: String,
    val cause: Throwable? = null,
)

internal class SceneActionExecutor(
    private val instance: Instance,
    private val identity: SceneRuntimeIdentity,
    private val elements: Map<LocalId, LogicalElementState>,
    private val activeElements: Map<LocalId, ActiveElement> = emptyMap(),
    private val viewers: ViewerStateStore,
    private val effects: SceneEffectSink,
    private val actions: Map<ActionKey, SceneActionHandler>,
    private val isCurrent: (PendingActionChain) -> Boolean,
    private val schedule: (Runnable) -> Unit = { instance.scheduler().execute(it) },
    private val registerCompletion:
        (CompletionStage<SceneActionResult>, BiConsumer<SceneActionResult?, Throwable?>) -> Unit =
        { stage, callback ->
            stage.whenComplete(callback)
        },
    private val clock: SceneClock,
    private val reportDiagnostic: (SceneActionDiagnostic) -> Unit = {},
    private val reportFallbackDiagnostic: (SceneActionDiagnostic) -> Unit = reportDiagnostic,
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
                try {
                    executeSafe(chain, action)
                } catch (error: Throwable) {
                    report(
                        chain,
                        ChainOutcome.FAILED,
                        "SAFE_ACTION_FAILED",
                        "Scene action failed.",
                        error,
                    )
                    ChainOutcome.FAILED
                }
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
                actions[action.key]?.execute(context)
                    ?: throw IllegalStateException("No handler for ${action.key.value}.")
            } catch (error: Throwable) {
                report(
                    chain,
                    ChainOutcome.FAILED,
                    "APPLICATION_ACTION_START_FAILED",
                    "Application action could not start.",
                    error,
                )
                completeFailureOrStale(chain, completion)
                return
            }
        val callbackScheduleFailure =
            ActionDiagnosticFields(
                chain.input.playerId,
                chain.input.npcId,
                chain.input.trigger,
                chain.key.bindingIndex,
            )
        try {
            registerCompletion(
                stage,
                BiConsumer { result, failure ->
                    try {
                        schedule(
                            Runnable {
                                try {
                                    completeApplication(
                                        chain,
                                        actionIndex,
                                        completion,
                                        result,
                                        failure,
                                    )
                                } catch (error: Throwable) {
                                    report(
                                        chain,
                                        ChainOutcome.FAILED,
                                        "APPLICATION_CALLBACK_FAILED",
                                        "Application action callback failed.",
                                        error,
                                    )
                                    completeFailureOrStale(chain, completion)
                                }
                            }
                        )
                    } catch (error: Throwable) {
                        reportFallback(
                            callbackScheduleFailure.diagnostic(
                                ChainOutcome.FAILED,
                                "APPLICATION_CALLBACK_SCHEDULE_FAILED",
                                "Application action callback could not be scheduled.",
                                error,
                            )
                        )
                        completion.complete(ChainOutcome.FAILED)
                    }
                },
            )
        } catch (error: Throwable) {
            report(
                chain,
                ChainOutcome.FAILED,
                "APPLICATION_CALLBACK_REGISTRATION_FAILED",
                "Application action callback registration failed.",
                error,
            )
            completeFailureOrStale(chain, completion)
        }
    }

    private fun completeApplication(
        chain: PendingActionChain,
        actionIndex: Int,
        completion: CompletableFuture<ChainOutcome>,
        result: SceneActionResult?,
        failure: Throwable?,
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
        when {
            failure != null -> {
                report(
                    chain,
                    ChainOutcome.FAILED,
                    "APPLICATION_STAGE_FAILED",
                    "Application action completed exceptionally.",
                    failure,
                )
                completion.complete(ChainOutcome.FAILED)
            }
            result is SceneActionResult.Success -> runNext(chain, actionIndex + 1, completion)
            result is SceneActionResult.Rejected -> {
                report(
                    chain,
                    ChainOutcome.REJECTED,
                    "APPLICATION_REJECTED",
                    result.diagnostic,
                    null,
                )
                completion.complete(ChainOutcome.REJECTED)
            }
            result is SceneActionResult.Failure -> {
                report(
                    chain,
                    ChainOutcome.FAILED,
                    "APPLICATION_FAILED",
                    result.diagnostic,
                    result.cause,
                )
                completion.complete(ChainOutcome.FAILED)
            }
            else -> {
                report(
                    chain,
                    ChainOutcome.FAILED,
                    "APPLICATION_RESULT_MISSING",
                    "Application action returned no result.",
                    null,
                )
                completion.complete(ChainOutcome.FAILED)
            }
        }
    }

    private fun executeSafe(chain: PendingActionChain, action: SceneAction): ChainOutcome? {
        val player = instance.getPlayerByUuid(chain.input.playerId)
        if (player == null || player.instance !== instance) return ChainOutcome.STALE
        when (action) {
            is StartAnimationAction -> {
                val target = requireTarget(action.target)
                target.state.setAnimation(
                    target.partId,
                    LogicalAnimationState(action.animation, clock.nanoTime()),
                )
                target.activeHandles.forEach { it.startAnimation(action.animation, 0) }
            }
            is StopAnimationAction -> {
                val target = requireTarget(action.target)
                if (
                    action.animation == null ||
                        target.state.animationFor(target.partId).animation == action.animation
                )
                    target.state.setAnimation(target.partId, LogicalAnimationState(null, null))
                target.activeHandles.forEach { it.stopAnimation(action.animation) }
            }
            is SetViewerScaleAction -> {
                val target = requireTarget(action.target)
                val key = ViewerElementKey(player.uuid, target.state.element.id, target.partId)
                viewers.setScale(key, action.multiplier, action.transitionMillis)
                applyViewerState(target, key)
            }
            is SetViewerHighlightAction -> {
                val target = requireTarget(action.target)
                val key = ViewerElementKey(player.uuid, target.state.element.id, target.partId)
                viewers.setHighlight(key, action.enabled, action.transitionMillis)
                applyViewerState(target, key)
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

    private fun applyViewerState(target: ResolvedActionTarget, key: ViewerElementKey) {
        activeElements[target.state.element.id]
            ?.takeIf { it.generation == target.state.generation }
            ?.let { viewers.apply(instance, it, key) }
    }

    private fun report(
        chain: PendingActionChain,
        outcome: ChainOutcome,
        code: String,
        diagnostic: String,
        cause: Throwable?,
    ) {
        report(
            ActionDiagnosticFields(
                    chain.input.playerId,
                    chain.input.npcId,
                    chain.input.trigger,
                    chain.key.bindingIndex,
                )
                .diagnostic(outcome, code, diagnostic, cause)
        )
    }

    private fun report(diagnostic: SceneActionDiagnostic) {
        try {
            reportDiagnostic(diagnostic)
        } catch (_: Throwable) {
            // Reporting must never replace the action outcome.
        }
    }

    private fun reportFallback(diagnostic: SceneActionDiagnostic) {
        try {
            reportFallbackDiagnostic(diagnostic)
        } catch (_: Throwable) {
            // Reporting must never replace the action outcome.
        }
    }

    private data class ActionDiagnosticFields(
        val playerId: java.util.UUID,
        val elementId: LocalId,
        val trigger: SceneTrigger,
        val bindingIndex: Int,
    ) {
        fun diagnostic(outcome: ChainOutcome, code: String, text: String, cause: Throwable?) =
            SceneActionDiagnostic(
                playerId,
                elementId,
                trigger,
                bindingIndex,
                outcome,
                code,
                text,
                cause,
            )
    }
}
