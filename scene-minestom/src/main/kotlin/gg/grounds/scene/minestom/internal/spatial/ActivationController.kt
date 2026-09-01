package gg.grounds.scene.minestom.internal.spatial

import gg.grounds.scene.format.ActivationPolicy
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.SceneRuntimeConfig
import net.minestom.server.coordinate.Point

internal enum class ActivationTransitionKind { ACTIVATE, DEACTIVATE }
internal data class ActivationTransition(val elementId: LocalId, val kind: ActivationTransitionKind)

internal class ActivationController(
    private val index: SpatialIndex,
    private val config: SceneRuntimeConfig,
    private val clock: SceneClock,
) {
    private enum class RuntimeState { INACTIVE, ACTIVE, FAILED }

    private val states = index.elements().associate { it.id to RuntimeState.INACTIVE }.toMutableMap()
    private val pending = mutableSetOf<ActivationTransition>()
    private val outsideSince = mutableMapOf<LocalId, Long>()
    private val queued = mutableListOf<ActivationTransition>()

    fun evaluate(eligiblePlayerPositions: List<Point>): List<ActivationTransition> {
        val nearActivation = eligiblePlayerPositions.flatMap { index.candidates(it, config.activationDistance) }.toSet()
        val nearDeactivation = eligiblePlayerPositions.flatMap { index.candidates(it, config.deactivationDistance) }.toSet()
        val now = clock.nanoTime()
        index.elements().forEach { element ->
            when (states.getValue(element.id)) {
                RuntimeState.FAILED -> Unit
                RuntimeState.INACTIVE -> if (element.policy == ActivationPolicy.ALWAYS || element.id in nearActivation) request(element.id, ActivationTransitionKind.ACTIVATE)
                RuntimeState.ACTIVE -> evaluateActive(element, nearDeactivation, now)
            }
        }
        queued.sortWith(transitionOrder)
        return queued.take(config.transitionBudgetPerTick).also { queued.subList(0, it.size).clear() }
    }

    fun markActive(elementId: LocalId) {
        states[elementId] = RuntimeState.ACTIVE
        clearPending(elementId)
    }

    fun markInactive(elementId: LocalId) {
        states[elementId] = RuntimeState.INACTIVE
        outsideSince.remove(elementId)
        clearPending(elementId)
    }

    fun markFailed(elementId: LocalId) {
        states[elementId] = RuntimeState.FAILED
        outsideSince.remove(elementId)
        clearPending(elementId)
    }

    private fun evaluateActive(element: IndexedElement, nearDeactivation: Set<LocalId>, now: Long) {
        if (element.policy == ActivationPolicy.ALWAYS || element.id in nearDeactivation) {
            outsideSince.remove(element.id)
            return
        }
        val since = outsideSince.getOrPut(element.id) { now }
        if (elapsedAtLeast(now, since, graceNanos())) request(element.id, ActivationTransitionKind.DEACTIVATE)
    }

    private fun request(elementId: LocalId, kind: ActivationTransitionKind) {
        val transition = ActivationTransition(elementId, kind)
        if (pending.add(transition)) queued += transition
    }

    private fun graceNanos(): Long = if (config.deactivationGraceMillis > Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else config.deactivationGraceMillis * 1_000_000L
    private fun elapsedAtLeast(now: Long, since: Long, duration: Long): Boolean = java.lang.Long.compareUnsigned(now - since, duration) >= 0

    private fun clearPending(elementId: LocalId) {
        pending.removeAll { it.elementId == elementId }
        queued.removeAll { it.elementId == elementId }
    }

    private val transitionOrder = compareBy<ActivationTransition>(
        { index.cellKey(it.elementId).x },
        { index.cellKey(it.elementId).z },
        { it.elementId.value },
        { it.kind },
    )
}
