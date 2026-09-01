package gg.grounds.scene.minestom.internal.spatial

import gg.grounds.scene.format.ActivationPolicy
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.SceneRuntimeConfig
import gg.grounds.scene.minestom.internal.elapsedAtLeast
import gg.grounds.scene.minestom.internal.millisToNanosSaturated
import net.minestom.server.coordinate.Point

internal enum class ActivationTransitionKind {
    ACTIVATE,
    DEACTIVATE,
}

internal data class ActivationTransition(val elementId: LocalId, val kind: ActivationTransitionKind)

internal class ActivationController(
    private val index: SpatialIndex,
    private val config: SceneRuntimeConfig,
    private val clock: SceneClock,
) {
    private val elementsById = index.elements().associateBy { it.id }
    private val always =
        index
            .elements()
            .filter { it.policy == ActivationPolicy.ALWAYS }
            .mapTo(linkedSetOf()) { it.id }
    private val inactive =
        index
            .elements()
            .filter { it.policy == ActivationPolicy.AUTOMATIC }
            .mapTo(linkedSetOf()) { it.id }
    private val active = linkedSetOf<LocalId>()
    private val activating = linkedSetOf<LocalId>()
    private val failed = linkedSetOf<LocalId>()
    private val pending = mutableSetOf<ActivationTransition>()
    private val outsideSince = mutableMapOf<LocalId, Long>()
    private val queued = mutableListOf<ActivationTransition>()
    private var lastVisited = 0
    private val orderRank =
        index.elements().mapIndexed { rank, element -> element.id to rank }.toMap()

    init {
        always.forEach { request(it, ActivationTransitionKind.ACTIVATE) }
    }

    fun evaluate(eligiblePlayerPositions: List<Point>): List<ActivationTransition> {
        reevaluate(eligiblePlayerPositions)
        return drainTransitions()
    }

    fun reevaluate(eligiblePlayerPositions: List<Point>): List<LocalId> {
        val visited = linkedSetOf<LocalId>()
        val nearActivation = candidates(eligiblePlayerPositions, config.activationDistance, visited)
        val nearDeactivation =
            candidates(eligiblePlayerPositions, config.deactivationDistance, visited)
        lastVisited = (visited + active + activating).size
        val now = clock.nanoTime()

        pending
            .filter { it.kind == ActivationTransitionKind.ACTIVATE }
            .map { it.elementId }
            .filter { it !in always && it !in nearActivation }
            .forEach { cancel(it, ActivationTransitionKind.ACTIVATE) }
        nearActivation
            .asSequence()
            .filter { it in inactive && it !in failed }
            .sortedWith(elementOrder)
            .forEach { request(it, ActivationTransitionKind.ACTIVATE) }
        active.toList().forEach { elementId ->
            evaluateActive(elementsById.getValue(elementId), nearDeactivation, now)
        }
        val invalidated =
            activating
                .filter { it !in always && it !in nearActivation }
                .sortedWith(elementOrder)
                .also { ids ->
                    ids.forEach { elementId ->
                        activating.remove(elementId)
                        inactive.add(elementId)
                    }
                }
        queued.sortWith(transitionOrder)
        return invalidated
    }

    fun drainTransitions(): List<ActivationTransition> =
        queued.take(config.transitionBudgetPerTick).also { queued.subList(0, it.size).clear() }

    fun markActivating(elementId: LocalId) {
        if (elementId in failed) return
        inactive.remove(elementId)
        activating.add(elementId)
        cancel(elementId, ActivationTransitionKind.ACTIVATE)
    }

    fun markActive(elementId: LocalId) {
        inactive.remove(elementId)
        activating.remove(elementId)
        if (elementId !in always) active.add(elementId)
        clearPending(elementId)
    }

    fun markInactive(elementId: LocalId) {
        active.remove(elementId)
        activating.remove(elementId)
        if (elementId !in always && elementId !in failed) inactive.add(elementId)
        outsideSince.remove(elementId)
        clearPending(elementId)
    }

    fun markFailed(elementId: LocalId) {
        inactive.remove(elementId)
        active.remove(elementId)
        activating.remove(elementId)
        failed.add(elementId)
        outsideSince.remove(elementId)
        clearPending(elementId)
    }

    fun visitedElementCount(): Int = lastVisited

    private fun evaluateActive(element: IndexedElement, nearDeactivation: Set<LocalId>, now: Long) {
        if (element.policy == ActivationPolicy.ALWAYS || element.id in nearDeactivation) {
            outsideSince.remove(element.id)
            cancel(element.id, ActivationTransitionKind.DEACTIVATE)
            return
        }
        val since = outsideSince.getOrPut(element.id) { now }
        if (elapsedAtLeast(now, since, graceNanos()))
            request(element.id, ActivationTransitionKind.DEACTIVATE)
    }

    private fun request(elementId: LocalId, kind: ActivationTransitionKind) {
        val transition = ActivationTransition(elementId, kind)
        if (pending.add(transition)) queued += transition
    }

    private fun graceNanos(): Long = millisToNanosSaturated(config.deactivationGraceMillis)

    private fun clearPending(elementId: LocalId) {
        pending.removeAll { it.elementId == elementId }
        queued.removeAll { it.elementId == elementId }
    }

    private fun cancel(elementId: LocalId, kind: ActivationTransitionKind) {
        val transition = ActivationTransition(elementId, kind)
        pending.remove(transition)
        queued.remove(transition)
    }

    private val transitionOrder =
        compareBy<ActivationTransition>({ orderRank.getValue(it.elementId) }, { it.kind })

    private val elementOrder = compareBy<LocalId> { orderRank.getValue(it) }

    private fun candidates(
        positions: List<Point>,
        radius: Double,
        visited: MutableSet<LocalId>,
    ): Set<LocalId> =
        positions
            .flatMap { point ->
                index.candidates(point, radius).also { visited += index.visitedIds() }
            }
            .toSet()
}
