package gg.grounds.scene.minestom.internal

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import gg.grounds.scene.minestom.internal.action.ChainOutcome
import gg.grounds.scene.minestom.internal.action.SceneActionDiagnostic
import gg.grounds.scene.minestom.internal.action.SceneActionExecutor
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import gg.grounds.scene.minestom.internal.runtime.ElementActivation
import gg.grounds.scene.minestom.internal.runtime.ElementActivator
import gg.grounds.scene.minestom.internal.runtime.LogicalAnimationState
import gg.grounds.scene.minestom.internal.runtime.LogicalElementState
import gg.grounds.scene.minestom.internal.spatial.ActivationController
import gg.grounds.scene.minestom.internal.spatial.ActivationTransition
import gg.grounds.scene.minestom.internal.spatial.ActivationTransitionKind
import gg.grounds.scene.minestom.internal.spatial.IndexedElement
import gg.grounds.scene.minestom.internal.spatial.SpatialIndex
import gg.grounds.scene.minestom.internal.trigger.BindingKey
import gg.grounds.scene.minestom.internal.trigger.PendingActionChain
import gg.grounds.scene.minestom.internal.trigger.SceneTriggerInput
import gg.grounds.scene.minestom.internal.trigger.TriggerEngine
import gg.grounds.scene.minestom.internal.view.LookController
import gg.grounds.scene.minestom.internal.view.NpcSensorEngine
import gg.grounds.scene.minestom.internal.view.ViewerStateStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import org.slf4j.LoggerFactory

internal class DefaultSceneRuntime
private constructor(
    private val request: SceneRuntimeRequest,
    private val capabilities: SceneRuntimeCapabilities,
    private val ownerSchedule: (Runnable) -> Unit,
) : SceneRuntime {
    override val identity: SceneRuntimeIdentity = request.identity

    @Volatile private var closed = false
    override val isClosed: Boolean
        get() = closed

    private var runtimeGeneration = 0L
    private var tickIndex = 0L
    private var task: Task? = null
    private var eventNodeAttached = false
    private val creation = CompletableFuture<SceneRuntimeCreationResult>()
    private val closeLock = Any()
    private var closeCompletion: CompletableFuture<Void>? = null

    private val logicalStates: MutableMap<LocalId, LogicalElementState>
    private val activeElements = linkedMapOf<LocalId, ActiveElement>()
    private val pendingActivations = linkedMapOf<LocalId, PendingActivation>()
    private val chainOwners = mutableMapOf<ChainId, ChainOwner>()
    private val viewers = ViewerStateStore(request.clock)
    private val sensors = NpcSensorEngine(request.instance, request.playerPolicy)
    private val look = LookController(request.clock, request.playerPolicy)
    private val spatial: ActivationController
    private val activator: ElementActivator
    private val triggerEngine: TriggerEngine
    private val actionExecutor: SceneActionExecutor
    private val events: MinestomSceneEvents

    init {
        val now = request.clock.nanoTime()
        logicalStates =
            request.scene.elements
                .sortedBy { it.id.value }
                .associateTo(linkedMapOf()) { element ->
                    val animation = element.initialAnimation()
                    element.id to
                        LogicalElementState(
                            element,
                            LogicalAnimationState(animation, now.takeIf { animation != null }),
                            0,
                        )
                }
        val index =
            SpatialIndex(
                logicalStates.values.map {
                    IndexedElement(
                        it.element.id,
                        it.element.activation,
                        it.element.transform.position,
                    )
                },
                request.config.cellEdge,
            )
        spatial = ActivationController(index, request.config, request.clock)
        activator =
            ElementActivator(
                request.instance,
                capabilities.rendererFactories,
                request.clock,
                ::marshal,
            )
        triggerEngine =
            TriggerEngine(
                request.instance,
                request.instance::getPlayerByUuid,
                request.playerPolicy,
                bindingsFor = { elementId ->
                    (logicalStates[elementId]?.element as? Npc)?.bindings.orEmpty()
                },
            )
        actionExecutor =
            SceneActionExecutor(
                request.instance,
                request.identity,
                logicalStates,
                activeElements,
                viewers,
                request.effects,
                capabilities.actionHandlers,
                ::isCurrent,
                ::marshal,
                clock = request.clock,
                reportDiagnostic = ::reportActionDiagnostic,
            )
        events =
            MinestomSceneEvents(
                "scene-runtime-${request.scene.id.value}-${request.instance.uuid}",
                activator::interactionElementId,
                ::activeElement,
                request.clock::nanoTime,
                ::acceptTrigger,
                ::removePlayerState,
            )
    }

    private fun start(): CompletionStage<SceneRuntimeCreationResult> {
        try {
            marshal(Runnable(::beginInstallation))
        } catch (_: Throwable) {
            creation.complete(
                SceneRuntimeCreationResult.Failure(
                    listOf(runtimeProblem("Runtime installation could not be scheduled."))
                )
            )
        }
        return creation
    }

    private fun beginInstallation() {
        val always =
            logicalStates.values
                .filter { it.element.activation == ActivationPolicy.ALWAYS }
                .map { it.element.id }
        activatePrepared(always, 0)
    }

    private fun activatePrepared(always: List<LocalId>, index: Int) {
        val elementId = always.getOrNull(index)
        if (elementId == null) {
            installNodeAndTask()
            return
        }
        val state = logicalStates.getValue(elementId)
        val generation = state.generation
        val transition = ActivationTransition(elementId, ActivationTransitionKind.ACTIVATE, 0L)
        if (!spatial.beginActivation(transition)) {
            failInstallation(elementId, IllegalStateException("Activation desire is stale."))
            return
        }
        val activation = activator.beginActivation(state)
        val stage = activation.stage
        try {
            stage.whenComplete { active, error ->
                scheduleContinuation(
                    onRejected = { continuationError ->
                        val failure = error?.unwrap().withSuppressed(continuationError)
                        activation.abort()?.let(failure::addSuppressedDistinct)
                        logFailure("CONTINUATION_SCHEDULE_FAILED", elementId, null, failure)
                        creation.complete(
                            SceneRuntimeCreationResult.Failure(
                                listOf(
                                    runtimeProblem(
                                        "Runtime installation continuation could not be scheduled."
                                    )
                                )
                            )
                        )
                    }
                ) {
                    if (
                        error != null ||
                            active == null ||
                            state.generation != generation ||
                            !spatial.isActivationCurrent(elementId, transition.epoch)
                    ) {
                        val failure = error?.unwrap()
                        val cleanupFailure = activation.abort()
                        failInstallation(elementId, failure ?: cleanupFailure)
                    } else if (!spatial.markActive(elementId, transition.epoch)) {
                        val failure = IllegalStateException("Activation desire is stale.")
                        activation.abort()?.let(failure::addSuppressedDistinct)
                        failInstallation(elementId, failure)
                    } else if (!activation.claim(active)) {
                        val failure =
                            IllegalStateException("Activation ownership could not be claimed.")
                        activation.abort()?.let(failure::addSuppressedDistinct)
                        failInstallation(elementId, failure)
                    } else {
                        activeElements[elementId] = active
                        sensors.activate(active)
                        activatePrepared(always, index + 1)
                    }
                }
            }
        } catch (error: Throwable) {
            activation.abort()?.let(error::addSuppressedDistinct)
            failInstallation(elementId, error)
        }
    }

    private fun installNodeAndTask() {
        try {
            request.instance.eventNode().addChild(events.node)
            eventNodeAttached = true
            task =
                request.instance
                    .scheduler()
                    .buildTask(::tick)
                    .repeat(TaskSchedule.tick(1))
                    .schedule()
            creation.complete(SceneRuntimeCreationResult.Success(this))
        } catch (error: Throwable) {
            failInstallation(null, error)
        }
    }

    private fun failInstallation(elementId: LocalId?, error: Throwable? = null) {
        task?.cancel()
        task = null
        detachEventNode()
        logicalStates.values.forEach { it.generation++ }
        closeActiveElements()
        activeElements.clear()
        val problem =
            if (elementId == null) runtimeProblem("Runtime installation failed.")
            else
                SceneRuntimeProblem(
                    SceneRuntimeProblemCode.ACTIVATION_FAILED,
                    "elements/${elementId.value}",
                    elementId,
                    "Element ${elementId.value} failed to activate.",
                )
        error?.let {
            logFailure(
                if (elementId == null) "INSTALLATION_FAILED" else "ACTIVATION_FAILED",
                elementId,
                null,
                it,
            )
        }
        creation.complete(SceneRuntimeCreationResult.Failure(listOf(problem)))
    }

    private fun tick() {
        if (closed) return
        try {
            tickRuntime()
        } catch (error: Throwable) {
            logFailure("TICK_FAILED", null, null, error)
        }
    }

    private fun tickRuntime() {
        val players = request.instance.players.sortedBy { it.uuid.toString() }
        val eligible =
            players.filter { player ->
                try {
                    request.playerPolicy.isEligible(player).also { isEligible ->
                        if (!isEligible) removePlayerState(player)
                    }
                } catch (error: Throwable) {
                    logFailure("PLAYER_POLICY_FAILED", null, player, error)
                    removePlayerState(player)
                    false
                }
            }
        val active = activeElements.values.sortedBy { it.elementId.value }
        advanceAnimations(active)
        viewers.applyTransitions(request.instance, activeElements) { key, error ->
            logFailure("VIEWER_TRANSITION_FAILED", key.elementId, player(key.playerId), error)
        }
        look.update(eligible, active) { element, error ->
            logFailure("LOOK_UPDATE_FAILED", element.elementId, null, error)
        }
        active.forEach(sensors::refresh)
        try {
            sensors.update(eligible).forEach { transition ->
                acceptTrigger(
                    SceneTriggerInput(
                        transition.playerId,
                        transition.elementId,
                        transition.trigger,
                        null,
                        request.clock.nanoTime(),
                    )
                )
            }
        } catch (error: Throwable) {
            logFailure("SENSOR_UPDATE_FAILED", null, null, error)
        }
        if (tickIndex++ % request.config.spatialIntervalTicks == 0L) {
            try {
                spatial
                    .reevaluate(eligible.map(Player::getPosition))
                    .forEach(::abortPendingActivation)
            } catch (error: Throwable) {
                logFailure("SPATIAL_EVALUATION_FAILED", null, null, error)
            }
        }
        spatial.drainTransitions().forEach(::applyTransitionSafely)
    }

    private fun advanceAnimations(active: List<ActiveElement>) {
        active.forEach { element ->
            val state = logicalStates[element.elementId] ?: return@forEach
            element.handleEntries().forEach handleLoop@{ (partId, handle) ->
                val started = state.animationFor(partId).startedNanos ?: return@handleLoop
                val elapsedMillis = elapsedNanos(request.clock.nanoTime(), started) / 1_000_000
                try {
                    handle.advanceAnimation(elapsedMillis)
                } catch (error: Throwable) {
                    logFailure("ANIMATION_UPDATE_FAILED", element.elementId, null, error)
                }
            }
        }
    }

    private fun applyTransitionSafely(transition: ActivationTransition) {
        try {
            when (transition.kind) {
                ActivationTransitionKind.ACTIVATE -> activateAutomatic(transition)
                ActivationTransitionKind.DEACTIVATE -> deactivate(transition.elementId)
            }
        } catch (error: Throwable) {
            logFailure("TRANSITION_FAILED", transition.elementId, null, error)
            spatial.markFailed(transition.elementId)
        }
    }

    private fun activateAutomatic(transition: ActivationTransition) {
        val elementId = transition.elementId
        if (closed || !spatial.beginActivation(transition)) return
        if (elementId in activeElements || elementId in pendingActivations) {
            spatial.markFailed(elementId)
            return
        }
        val state =
            logicalStates[elementId]
                ?: run {
                    spatial.markFailed(elementId)
                    return
                }
        val generation = state.generation
        val activation = activator.beginActivation(state)
        val stage = activation.stage
        val pending = PendingActivation(transition.epoch, activation)
        pendingActivations[elementId] = pending
        try {
            stage.whenComplete { active, error ->
                scheduleContinuation(
                    onRejected = { continuationError ->
                        val failure = error?.unwrap().withSuppressed(continuationError)
                        activation.abort()?.let(failure::addSuppressedDistinct)
                        logFailure("CONTINUATION_SCHEDULE_FAILED", elementId, null, failure)
                    }
                ) {
                    pendingActivations.remove(elementId, pending)
                    if (closed || state.generation != generation) {
                        activation.abort()
                    } else if (
                        !isActivationEligible(state.element) ||
                            !spatial.isActivationCurrent(elementId, pending.epoch)
                    ) {
                        reevaluateActivationDesires().forEach(::abortPendingActivation)
                        activation.abort()
                    } else if (error != null || active == null) {
                        spatial.markFailed(elementId)
                        logFailure("ACTIVATION_FAILED", elementId, null, error?.unwrap())
                    } else {
                        try {
                            viewers.keysForElement(elementId).forEach {
                                viewers.apply(request.instance, active, it)
                            }
                            if (!spatial.isActivationCurrent(elementId, pending.epoch)) {
                                activation.abort()
                                spatial.markFailed(elementId)
                            } else if (!spatial.markActive(elementId, pending.epoch)) {
                                activation.abort()
                                spatial.markFailed(elementId)
                            } else if (activation.claim(active)) {
                                activeElements[elementId] = active
                                sensors.activate(active)
                            } else {
                                activation.abort()
                                spatial.markFailed(elementId)
                            }
                        } catch (applyFailure: Throwable) {
                            activation.abort()
                            spatial.markFailed(elementId)
                            logFailure("VIEWER_REAPPLY_FAILED", elementId, null, applyFailure)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            pendingActivations.remove(elementId, pending)
            activation.abort()?.let(error::addSuppressedDistinct)
            spatial.markFailed(elementId)
            logFailure("ACTIVATION_CALLBACK_REGISTRATION_FAILED", elementId, null, error)
        }
    }

    private fun deactivate(elementId: LocalId) {
        val state = logicalStates[elementId] ?: return
        state.generation++
        pendingActivations.remove(elementId)?.let { pending ->
            pending.activation.abort()?.let { error ->
                logFailure("ACTIVATION_ABORT_FAILED", elementId, null, error)
            }
        }
        invalidateElementChains(elementId)
        look.removeElement(elementId)
        sensors.deactivate(elementId)
        activeElements.remove(elementId)?.let(activator::deactivate)
        spatial.markInactive(elementId)
    }

    private fun abortPendingActivation(elementId: LocalId) {
        if (elementId !in pendingActivations) return
        deactivate(elementId)
    }

    private fun isActivationEligible(
        element: SceneElement,
        excludedPlayerId: java.util.UUID? = null,
    ): Boolean {
        if (element.activation == ActivationPolicy.ALWAYS) return true
        val radiusSquared = request.config.activationDistance * request.config.activationDistance
        return request.instance.players.any { player ->
            if (player.uuid == excludedPlayerId) return@any false
            val eligible =
                try {
                    request.playerPolicy.isEligible(player)
                } catch (error: Throwable) {
                    logFailure("PLAYER_POLICY_FAILED", element.id, player, error)
                    false
                }
            if (!eligible) return@any false
            val dx = player.position.x() - element.transform.position.x
            val dz = player.position.z() - element.transform.position.z
            dx * dx + dz * dz <= radiusSquared
        }
    }

    private fun acceptTrigger(input: SceneTriggerInput) {
        if (closed) return
        if (activeElement(input.npcId) == null) return
        val state = logicalStates[input.npcId] ?: return
        triggerEngine.accept(input).forEach { chain ->
            val chainId = ChainId(chain.key, chain.generation)
            chainOwners[chainId] = ChainOwner(runtimeGeneration, state.generation)
            val completion =
                try {
                    actionExecutor.execute(chain)
                } catch (error: Throwable) {
                    logFailure("ACTION_CHAIN_FAILED", input.npcId, player(input.playerId), error)
                    chainOwners.remove(chainId)
                    return@forEach
                }
            completion.whenComplete { outcome, error ->
                scheduleContinuation(
                    onRejected = { continuationError ->
                        val failure = error?.unwrap().withSuppressed(continuationError)
                        logFailure("CONTINUATION_SCHEDULE_FAILED", input.npcId, null, failure)
                    }
                ) {
                    if (chainOwners[chainId] == null) return@scheduleContinuation
                    if (error != null) {
                        logFailure(
                            "ACTION_CHAIN_FAILED",
                            input.npcId,
                            player(input.playerId),
                            error.unwrap(),
                        )
                    }
                    triggerEngine.complete(
                        chain.key,
                        chain.generation,
                        outcome == ChainOutcome.SUCCEEDED,
                        request.clock.nanoTime(),
                    )
                    chainOwners.remove(chainId)
                }
            }
        }
    }

    private fun isCurrent(chain: PendingActionChain): Boolean {
        val owner = chainOwners[ChainId(chain.key, chain.generation)] ?: return false
        val elementGeneration = logicalStates[chain.input.npcId]?.generation ?: return false
        return !closed &&
            owner.runtimeGeneration == runtimeGeneration &&
            owner.elementGeneration == elementGeneration
    }

    private fun removePlayerState(player: Player) {
        try {
            reevaluateActivationDesires(player.uuid).forEach(::abortPendingActivation)
        } catch (error: Throwable) {
            logFailure("PENDING_ACTIVATION_INVALIDATION_FAILED", null, player, error)
        }
        viewers.keysForPlayer(player.uuid).forEach { key ->
            activeElements[key.elementId]?.let { active ->
                try {
                    viewers.clear(request.instance, active, key)
                } catch (error: Throwable) {
                    logFailure("VIEWER_CLEANUP_FAILED", key.elementId, player, error)
                }
            }
        }
        try {
            viewers.removePlayer(player.uuid)
        } catch (error: Throwable) {
            logFailure("VIEWER_STATE_REMOVE_FAILED", null, player, error)
        }
        try {
            sensors.removePlayer(player.uuid)
        } catch (error: Throwable) {
            logFailure("SENSOR_STATE_REMOVE_FAILED", null, player, error)
        }
        try {
            triggerEngine.invalidatePlayer(player.uuid)
        } catch (error: Throwable) {
            logFailure("TRIGGER_STATE_REMOVE_FAILED", null, player, error)
        }
        try {
            chainOwners.keys.removeIf { it.key.playerId == player.uuid }
        } catch (error: Throwable) {
            logFailure("CHAIN_STATE_REMOVE_FAILED", null, player, error)
        }
    }

    private fun invalidateElementChains(elementId: LocalId) {
        triggerEngine.invalidateElement(elementId)
        chainOwners.keys.removeIf { it.key.npcId == elementId }
    }

    private fun reevaluateActivationDesires(
        excludedPlayerId: java.util.UUID? = null
    ): List<LocalId> =
        spatial.reevaluate(
            request.instance.players.mapNotNull { player ->
                if (player.uuid == excludedPlayerId) return@mapNotNull null
                val eligible =
                    try {
                        request.playerPolicy.isEligible(player)
                    } catch (error: Throwable) {
                        logFailure("PLAYER_POLICY_FAILED", null, player, error)
                        false
                    }
                player.position.takeIf { eligible }
            }
        )

    override fun close(): CompletionStage<Void> =
        synchronized(closeLock) {
            closeCompletion
                ?: CompletableFuture<Void>().also { completion ->
                    closeCompletion = completion
                    try {
                        marshal(Runnable { beginClose(completion) })
                    } catch (error: Throwable) {
                        completion.completeExceptionally(error)
                    }
                }
        }

    private fun beginClose(completion: CompletableFuture<Void>) {
        if (closed) {
            finishClose(completion)
            return
        }
        closed = true
        runtimeGeneration++
        logicalStates.values.forEach { it.generation++ }
        task?.cancel()
        task = null
        detachEventNode()
        triggerEngine.clear()
        chainOwners.clear()
        sensors.clear()
        activeElements.values.forEach { active ->
            viewers.keysForElement(active.elementId).forEach { key ->
                try {
                    viewers.clear(request.instance, active, key)
                } catch (error: Throwable) {
                    logFailure("VIEWER_CLEANUP_FAILED", active.elementId, null, error)
                }
            }
        }
        viewers.clear()
        closeActiveElements()
        activeElements.clear()
        pendingActivations.entries
            .sortedBy { it.key.value }
            .forEach { (elementId, pending) ->
                pending.activation.abort()?.let { error ->
                    logFailure("ELEMENT_CLOSE_FAILED", elementId, null, error)
                }
            }
        finishClose(completion)
    }

    private fun finishClose(completion: CompletableFuture<Void>) {
        pendingActivations.clear()
        logicalStates.clear()
        completion.complete(null)
    }

    private fun closeActiveElements() {
        activeElements.values
            .sortedBy { it.elementId.value }
            .forEach { active ->
                try {
                    activator.deactivate(active)
                } catch (error: Throwable) {
                    logFailure("ELEMENT_CLOSE_FAILED", active.elementId, null, error)
                }
            }
    }

    private fun detachEventNode() {
        if (!eventNodeAttached) return
        request.instance.eventNode().removeChild(events.node)
        eventNodeAttached = false
    }

    private fun activeElement(elementId: LocalId): ActiveElement? {
        val state = logicalStates[elementId] ?: return null
        return activeElements[elementId]?.takeIf { it.generation == state.generation }
    }

    private fun player(playerId: java.util.UUID) = request.instance.getPlayerByUuid(playerId)

    private fun marshal(action: Runnable) = ownerSchedule(action)

    private fun scheduleContinuation(onRejected: (Throwable) -> Unit, action: () -> Unit) {
        fun reject(error: Throwable, code: String) {
            try {
                onRejected(error)
            } catch (rejectionFailure: Throwable) {
                error.addSuppressedDistinct(rejectionFailure)
            }
            logFailure(code, null, null, error)
        }
        try {
            marshal(
                Runnable {
                    try {
                        action()
                    } catch (error: Throwable) {
                        reject(error, "CONTINUATION_CALLBACK_FAILED")
                    }
                }
            )
        } catch (error: Throwable) {
            reject(error, "CONTINUATION_SCHEDULE_FAILED")
        }
    }

    private fun reportActionDiagnostic(diagnostic: SceneActionDiagnostic) {
        LOGGER.error(
            "Scene action diagnostic code={} outcome={} scene={} element={} player={} trigger={} binding={} diagnostic={}",
            diagnostic.code,
            diagnostic.outcome,
            identity.sceneId.value,
            diagnostic.elementId.value,
            diagnostic.playerId,
            diagnostic.trigger,
            diagnostic.bindingIndex,
            diagnostic.diagnostic,
            diagnostic.cause,
        )
    }

    private fun runtimeProblem(message: String) =
        SceneRuntimeProblem(SceneRuntimeProblemCode.RUNTIME_FAILURE, "runtime", null, message)

    private fun logFailure(code: String, elementId: LocalId?, player: Player?, error: Throwable?) {
        LOGGER.error(
            "Scene runtime failure code={} scene={} element={} player={}",
            code,
            identity.sceneId.value,
            elementId?.value,
            player?.uuid,
            error,
        )
    }

    private data class ChainId(val key: BindingKey, val generation: Long)

    private data class ChainOwner(val runtimeGeneration: Long, val elementGeneration: Long)

    private data class PendingActivation(val epoch: Long, val activation: ElementActivation)

    companion object {
        private val LOGGER = LoggerFactory.getLogger(DefaultSceneRuntime::class.java)

        fun create(
            request: SceneRuntimeRequest,
            capabilities: SceneRuntimeCapabilities,
        ): CompletionStage<SceneRuntimeCreationResult> =
            DefaultSceneRuntime(request, capabilities, request.instance.scheduler()::execute)
                .start()

        internal fun create(
            request: SceneRuntimeRequest,
            capabilities: SceneRuntimeCapabilities,
            schedule: (Runnable) -> Unit,
        ): CompletionStage<SceneRuntimeCreationResult> =
            DefaultSceneRuntime(request, capabilities, schedule).start()
    }
}

private fun SceneElement.initialAnimation(): LocalId? =
    when (this) {
        is Prop -> initialAnimation
        is CompositeProp -> null
        is Npc -> initialAnimation
    }

private fun Throwable.unwrap(): Throwable = (this as? CompletionException)?.cause ?: this

private fun Throwable?.withSuppressed(secondary: Throwable): Throwable {
    val primary = this ?: return secondary
    primary.addSuppressedDistinct(secondary)
    return primary
}

private fun Throwable.addSuppressedDistinct(secondary: Throwable) {
    if (secondary !== this && suppressed.none { it === secondary }) addSuppressed(secondary)
}
