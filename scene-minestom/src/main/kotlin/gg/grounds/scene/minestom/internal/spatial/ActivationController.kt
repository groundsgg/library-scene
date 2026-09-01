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

internal data class ActivationTransition(
    val elementId: LocalId,
    val kind: ActivationTransitionKind,
    val epoch: Long,
)

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
    private val desireEpoch = mutableMapOf<LocalId, Long>()
    private val desired = linkedSetOf<LocalId>()
    private val pending = mutableSetOf<ActivationTransition>()
    private val outsideSince = mutableMapOf<LocalId, Long>()
    private val queued = mutableListOf<ActivationTransition>()
    private var lastVisited = 0
    private val orderRank =
        index.elements().mapIndexed { rank, element -> element.id to rank }.toMap()

    init {
        index
            .elements()
            .filter { it.policy == ActivationPolicy.AUTOMATIC }
            .forEach { desireEpoch[it.id] = 0L }
        always.forEach {
            desired.add(it)
            request(it, ActivationTransitionKind.ACTIVATE)
        }
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

        val nextDesired =
            nearActivation.filterTo(linkedSetOf()) {
                elementsById.getValue(it).policy == ActivationPolicy.AUTOMATIC
            }
        val invalidated = mutableListOf<LocalId>()
        desired
            .filter { it !in always && it !in nextDesired }
            .sortedWith(elementOrder)
            .forEach { elementId ->
                changeDesire(elementId, false)
                cancel(elementId, ActivationTransitionKind.ACTIVATE)
                if (activating.remove(elementId)) {
                    inactive.add(elementId)
                    invalidated += elementId
                }
            }
        nextDesired
            .filter { it !in desired }
            .sortedWith(elementOrder)
            .forEach { elementId ->
                changeDesire(elementId, true)
                if (elementId in inactive && elementId !in failed)
                    request(elementId, ActivationTransitionKind.ACTIVATE)
            }
        active.toList().forEach { elementId ->
            evaluateActive(elementsById.getValue(elementId), nearDeactivation, now)
        }
        queued.sortWith(transitionOrder)
        return invalidated
    }

    fun drainTransitions(): List<ActivationTransition> =
        queued.take(config.transitionBudgetPerTick).also { queued.subList(0, it.size).clear() }

    fun beginActivation(transition: ActivationTransition): Boolean {
        if (
            transition.kind != ActivationTransitionKind.ACTIVATE ||
                transition !in pending ||
                transition.elementId !in desired ||
                desireEpoch(transition.elementId) != transition.epoch ||
                transition.elementId in active ||
                transition.elementId in activating ||
                transition.elementId in failed
        )
            return false
        pending.remove(transition)
        queued.remove(transition)
        inactive.remove(transition.elementId)
        activating.add(transition.elementId)
        return true
    }

    fun isActivationCurrent(elementId: LocalId, epoch: Long): Boolean =
        elementId in desired && desireEpoch(elementId) == epoch && elementId in activating

    fun markActive(elementId: LocalId, epoch: Long): Boolean {
        if (!isActivationCurrent(elementId, epoch)) return false
        inactive.remove(elementId)
        activating.remove(elementId)
        if (elementId !in always) active.add(elementId)
        clearPending(elementId)
        return true
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
        val transition = ActivationTransition(elementId, kind, desireEpoch(elementId))
        if (pending.add(transition)) queued += transition
    }

    private fun changeDesire(elementId: LocalId, value: Boolean) {
        if ((elementId in desired) == value) return
        desireEpoch[elementId] = desireEpoch(elementId) + 1L
        if (value) desired.add(elementId) else desired.remove(elementId)
    }

    private fun desireEpoch(elementId: LocalId): Long = desireEpoch[elementId] ?: 0L

    private fun graceNanos(): Long = millisToNanosSaturated(config.deactivationGraceMillis)

    private fun clearPending(elementId: LocalId) {
        pending.removeAll { it.elementId == elementId }
        queued.removeAll { it.elementId == elementId }
    }

    private fun cancel(elementId: LocalId, kind: ActivationTransitionKind) {
        pending.removeAll { it.elementId == elementId && it.kind == kind }
        queued.removeAll { it.elementId == elementId && it.kind == kind }
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
