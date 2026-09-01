package gg.grounds.scene.minestom.internal

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import gg.grounds.scene.minestom.internal.action.ChainOutcome
import gg.grounds.scene.minestom.internal.action.SceneActionExecutor
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
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

internal class DefaultSceneRuntime private constructor(private val request: SceneRuntimeRequest) :
    SceneRuntime {
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
    private val pendingActivations = linkedMapOf<LocalId, CompletionStage<ActiveElement>>()
    private val chainOwners = mutableMapOf<ChainId, ChainOwner>()
    private val viewers = ViewerStateStore()
    private val sensors = NpcSensorEngine(request.playerPolicy)
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
        activator = ElementActivator(request.instance, request.renderers, request.clock, ::marshal)
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
                request.actionRegistry,
                ::isCurrent,
                ::marshal,
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
        val stage = activator.activate(state)
        stage.whenComplete { active, error ->
            scheduleContinuation {
                if (error != null || active == null || state.generation != generation) {
                    active?.let(activator::deactivate)
                    failInstallation(elementId)
                } else {
                    activeElements[elementId] = active
                    spatial.markActive(elementId)
                    activatePrepared(always, index + 1)
                }
            }
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
        } catch (_: Throwable) {
            failInstallation(null)
        }
    }

    private fun failInstallation(elementId: LocalId?) {
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
        look.update(eligible, active) { element, error ->
            logFailure("LOOK_UPDATE_FAILED", element.elementId, null, error)
        }
        eligible.forEach { player ->
            try {
                sensors.update(listOf(player), active).forEach { transition ->
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
                logFailure("SENSOR_UPDATE_FAILED", null, player, error)
            }
        }
        if (tickIndex++ % request.config.spatialIntervalTicks == 0L) {
            try {
                spatial.reevaluate(eligible.map(Player::getPosition))
            } catch (error: Throwable) {
                logFailure("SPATIAL_EVALUATION_FAILED", null, null, error)
            }
        }
        spatial.drainTransitions().forEach(::applyTransitionSafely)
    }

    private fun advanceAnimations(active: List<ActiveElement>) {
        active.forEach { element ->
            val state = logicalStates[element.elementId] ?: return@forEach
            val started = state.animation.startedNanos ?: return@forEach
            val elapsedMillis = (request.clock.nanoTime() - started).coerceAtLeast(0) / 1_000_000
            element.handles.forEach { handle ->
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
                ActivationTransitionKind.ACTIVATE -> activateAutomatic(transition.elementId)
                ActivationTransitionKind.DEACTIVATE -> deactivate(transition.elementId)
            }
        } catch (error: Throwable) {
            logFailure("TRANSITION_FAILED", transition.elementId, null, error)
            spatial.markFailed(transition.elementId)
        }
    }

    private fun activateAutomatic(elementId: LocalId) {
        if (elementId in activeElements || elementId in pendingActivations || closed) return
        val state = logicalStates[elementId] ?: return
        val generation = state.generation
        val stage = activator.activate(state)
        pendingActivations[elementId] = stage
        stage.whenComplete { active, error ->
            scheduleContinuation {
                pendingActivations.remove(elementId, stage)
                if (closed || state.generation != generation) {
                    active?.let(activator::deactivate)
                } else if (error != null || active == null) {
                    spatial.markFailed(elementId)
                    logFailure("ACTIVATION_FAILED", elementId, null, error?.unwrap())
                } else {
                    activeElements[elementId] = active
                    spatial.markActive(elementId)
                    viewers.keysForElement(elementId).forEach {
                        viewers.apply(request.instance, active, it)
                    }
                }
            }
        }
    }

    private fun deactivate(elementId: LocalId) {
        val state = logicalStates[elementId] ?: return
        state.generation++
        invalidateElementChains(elementId)
        look.removeElement(elementId)
        activeElements.remove(elementId)?.let(activator::deactivate)
        spatial.markInactive(elementId)
    }

    private fun acceptTrigger(input: SceneTriggerInput) {
        if (closed) return
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
                scheduleContinuation {
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
        viewers.keysForPlayer(player.uuid).forEach { key ->
            activeElements[key.elementId]?.let { active ->
                viewers.clear(request.instance, active, key)
            }
        }
        viewers.removePlayer(player.uuid)
        sensors.removePlayer(player.uuid)
        triggerEngine.invalidatePlayer(player.uuid)
        chainOwners.keys.removeIf { it.key.playerId == player.uuid }
    }

    private fun invalidateElementChains(elementId: LocalId) {
        triggerEngine.invalidateElement(elementId)
        chainOwners.keys.removeIf { it.key.npcId == elementId }
    }

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
        val pending = pendingActivations.values.map { it.toCompletableFuture() }
        if (pending.isEmpty()) finishClose(completion)
        else
            CompletableFuture.allOf(*pending.toTypedArray()).whenComplete { _, _ ->
                scheduleContinuation { finishClose(completion) }
            }
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

    private fun marshal(action: Runnable) = request.instance.scheduler().execute(action)

    private fun scheduleContinuation(action: () -> Unit) {
        try {
            marshal(Runnable(action))
        } catch (error: Throwable) {
            logFailure("CONTINUATION_SCHEDULE_FAILED", null, null, error)
        }
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

    companion object {
        private val LOGGER = LoggerFactory.getLogger(DefaultSceneRuntime::class.java)

        fun create(request: SceneRuntimeRequest): CompletionStage<SceneRuntimeCreationResult> =
            DefaultSceneRuntime(request).start()
    }
}

private fun SceneElement.initialAnimation(): LocalId? =
    when (this) {
        is Prop -> initialAnimation
        is CompositeProp -> null
        is Npc -> initialAnimation
    }

private fun Throwable.unwrap(): Throwable = (this as? CompletionException)?.cause ?: this
