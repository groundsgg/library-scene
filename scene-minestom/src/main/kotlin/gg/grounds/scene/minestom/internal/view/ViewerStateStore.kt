package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.SceneViewerHighlightTransition
import gg.grounds.scene.minestom.SceneViewerScaleTransition
import gg.grounds.scene.minestom.SceneViewerVisualState
import gg.grounds.scene.minestom.internal.elapsedAtLeast
import gg.grounds.scene.minestom.internal.elapsedNanos
import gg.grounds.scene.minestom.internal.millisToNanosSaturated
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import java.util.UUID
import net.minestom.server.instance.Instance

internal data class ViewerElementKey(
    val playerId: UUID,
    val elementId: LocalId,
    val partId: LocalId? = null,
)

internal class ViewerStateStore(private val clock: SceneClock = SceneClock { System.nanoTime() }) {
    private val states = mutableMapOf<ViewerElementKey, SceneViewerVisualState>()

    fun visualState(key: ViewerElementKey): SceneViewerVisualState {
        val current = states[key] ?: return SceneViewerVisualState()
        return evaluate(current, clock.nanoTime()).also { states[key] = it }
    }

    fun setScale(
        key: ViewerElementKey,
        multiplier: Double,
        transitionMillis: Long = 0L,
    ): SceneViewerVisualState {
        val now = clock.nanoTime()
        val current = evaluate(states[key] ?: SceneViewerVisualState(), now)
        val duration = millisToNanosSaturated(transitionMillis)
        val next =
            if (duration == 0L) {
                current.copy(scaleMultiplier = multiplier, scaleTransition = null)
            } else {
                current.copy(
                    scaleTransition =
                        SceneViewerScaleTransition(
                            now,
                            duration,
                            current.scaleMultiplier,
                            multiplier,
                            current.scaleMultiplier,
                        )
                )
            }
        states[key] = next
        return next
    }

    fun setHighlight(
        key: ViewerElementKey,
        enabled: Boolean,
        transitionMillis: Long = 0L,
    ): SceneViewerVisualState {
        val now = clock.nanoTime()
        val current = evaluate(states[key] ?: SceneViewerVisualState(), now)
        val duration = millisToNanosSaturated(transitionMillis)
        val next =
            if (duration == 0L) {
                current.copy(highlighted = enabled, highlightTransition = null)
            } else {
                current.copy(
                    highlightTransition =
                        SceneViewerHighlightTransition(
                            now,
                            duration,
                            current.highlighted,
                            enabled,
                            current.highlighted,
                        )
                )
            }
        states[key] = next
        return next
    }

    fun removePlayer(playerId: UUID) {
        states.keys.removeIf { it.playerId == playerId }
    }

    fun keysForPlayer(playerId: UUID): List<ViewerElementKey> =
        states.keys
            .filter { it.playerId == playerId }
            .sortedWith(compareBy({ it.elementId.value }, { it.partId?.value.orEmpty() }))

    fun keysForElement(elementId: LocalId): List<ViewerElementKey> =
        states.keys
            .filter { it.elementId == elementId }
            .sortedWith(compareBy({ it.playerId.toString() }, { it.partId?.value.orEmpty() }))

    fun apply(instance: Instance, active: ActiveElement, key: ViewerElementKey) {
        val player = instance.getPlayerByUuid(key.playerId) ?: return
        active.handlesFor(key.partId).forEach { it.applyViewerState(player, visualState(key)) }
    }

    fun clear(instance: Instance, active: ActiveElement, key: ViewerElementKey) {
        val player = instance.getPlayerByUuid(key.playerId) ?: return
        var failure: Throwable? = null
        active.handlesFor(key.partId).forEach { handle ->
            try {
                handle.clearViewerState(player)
            } catch (error: Throwable) {
                val current = failure
                if (current == null) failure = error
                else if (current !== error) current.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    fun applyTransitions(
        instance: Instance,
        activeElements: Map<LocalId, ActiveElement>,
        onFailure: (ViewerElementKey, Throwable) -> Unit,
    ) {
        states.keys
            .sortedWith(
                compareBy(
                    { it.playerId.toString() },
                    { it.elementId.value },
                    { it.partId?.value.orEmpty() },
                )
            )
            .forEach { key ->
                val state = visualState(key)
                if (state.scaleTransition == null && state.highlightTransition == null)
                    return@forEach
                val active = activeElements[key.elementId] ?: return@forEach
                try {
                    val player = instance.getPlayerByUuid(key.playerId) ?: return@forEach
                    active.handlesFor(key.partId).forEach { it.applyViewerState(player, state) }
                } catch (error: Throwable) {
                    onFailure(key, error)
                }
            }
    }

    fun clear() = states.clear()

    private fun evaluate(state: SceneViewerVisualState, now: Long): SceneViewerVisualState {
        val scale =
            state.scaleTransition?.let { transition ->
                val current =
                    if (elapsedAtLeast(now, transition.startedNanos, transition.durationNanos)) {
                        transition.target
                    } else {
                        val progress =
                            elapsedNanos(now, transition.startedNanos).toDouble() /
                                transition.durationNanos.toDouble()
                        transition.start + (transition.target - transition.start) * progress
                    }
                transition.copy(current = current)
            }
        val highlight =
            state.highlightTransition?.let { transition ->
                val current =
                    if (elapsedAtLeast(now, transition.startedNanos, transition.durationNanos)) {
                        transition.target
                    } else {
                        transition.start
                    }
                transition.copy(current = current)
            }
        return state.copy(
            scaleMultiplier = scale?.current ?: state.scaleMultiplier,
            highlighted = highlight?.current ?: state.highlighted,
            scaleTransition = scale,
            highlightTransition = highlight,
        )
    }
}
