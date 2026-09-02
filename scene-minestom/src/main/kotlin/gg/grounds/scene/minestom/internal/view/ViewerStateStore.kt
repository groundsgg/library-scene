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
    private data class ScaleState(
        val multiplier: Double,
        val transition: SceneViewerScaleTransition?,
    )

    private data class HighlightState(
        val highlighted: Boolean,
        val transition: SceneViewerHighlightTransition?,
    )

    private val scales = mutableMapOf<ViewerElementKey, ScaleState>()
    private val highlights = mutableMapOf<ViewerElementKey, HighlightState>()
    private val activeTransitions = mutableSetOf<ViewerElementKey>()

    fun visualState(key: ViewerElementKey): SceneViewerVisualState =
        effectiveState(key.playerId, key.elementId, key.partId)

    fun effectiveState(
        playerId: UUID,
        elementId: LocalId,
        partId: LocalId?,
    ): SceneViewerVisualState {
        val key = ViewerElementKey(playerId, elementId, partId)
        val root = ViewerElementKey(playerId, elementId)
        val now = clock.nanoTime()
        val scale = evaluateScale(scales[key] ?: scales[root] ?: ScaleState(1.0, null), now)
        val highlight =
            evaluateHighlight(
                highlights[key] ?: highlights[root] ?: HighlightState(false, null),
                now,
            )
        return SceneViewerVisualState(
            scale.multiplier,
            highlight.highlighted,
            scale.transition,
            highlight.transition,
        )
    }

    fun setScale(
        key: ViewerElementKey,
        multiplier: Double,
        transitionMillis: Long = 0L,
    ): SceneViewerVisualState {
        if (key.partId == null) clearPartOverrides(key.playerId, key.elementId, scales)
        val now = clock.nanoTime()
        val current = effectiveState(key.playerId, key.elementId, key.partId)
        val duration = millisToNanosSaturated(transitionMillis)
        scales[key] =
            if (duration == 0L) ScaleState(multiplier, null)
            else
                ScaleState(
                    current.scaleMultiplier,
                    SceneViewerScaleTransition(
                        now,
                        duration,
                        current.scaleMultiplier,
                        multiplier,
                        current.scaleMultiplier,
                    ),
                )
        syncTransition(key)
        return effectiveState(key.playerId, key.elementId, key.partId)
    }

    fun setHighlight(
        key: ViewerElementKey,
        enabled: Boolean,
        transitionMillis: Long = 0L,
    ): SceneViewerVisualState {
        if (key.partId == null) clearPartOverrides(key.playerId, key.elementId, highlights)
        val now = clock.nanoTime()
        val current = effectiveState(key.playerId, key.elementId, key.partId)
        val duration = millisToNanosSaturated(transitionMillis)
        highlights[key] =
            if (duration == 0L) HighlightState(enabled, null)
            else
                HighlightState(
                    current.highlighted,
                    SceneViewerHighlightTransition(
                        now,
                        duration,
                        current.highlighted,
                        enabled,
                        current.highlighted,
                    ),
                )
        syncTransition(key)
        return effectiveState(key.playerId, key.elementId, key.partId)
    }

    fun removePlayer(playerId: UUID) {
        scales.keys.removeIf { it.playerId == playerId }
        highlights.keys.removeIf { it.playerId == playerId }
        activeTransitions.removeIf { it.playerId == playerId }
    }

    fun keysForPlayer(playerId: UUID): List<ViewerElementKey> =
        keys().filter { it.playerId == playerId }.sortedWith(keyOrder)

    fun keysForElement(elementId: LocalId): List<ViewerElementKey> =
        keys().filter { it.elementId == elementId }.sortedWith(keyOrder)

    fun apply(instance: Instance, active: ActiveElement, key: ViewerElementKey) {
        val player = instance.getPlayerByUuid(key.playerId) ?: return
        active.handleEntries().forEach { (partId, handle) ->
            if (key.partId == null || key.partId == partId) {
                handle.applyViewerState(player, effectiveState(key.playerId, key.elementId, partId))
            }
        }
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
        activeTransitions.sortedWith(keyOrder).forEach { key ->
            settle(key, clock.nanoTime())
            val active = activeElements[key.elementId] ?: return@forEach
            try {
                apply(instance, active, key)
            } catch (error: Throwable) {
                onFailure(key, error)
            }
        }
    }

    fun activeTransitionCount(): Int = activeTransitions.size

    fun clear() {
        scales.clear()
        highlights.clear()
        activeTransitions.clear()
    }

    private fun settle(key: ViewerElementKey, now: Long) {
        scales[key]?.let { scale ->
            val evaluated = evaluateScale(scale, now)
            scales[key] =
                if (
                    evaluated.transition?.let {
                        elapsedAtLeast(now, it.startedNanos, it.durationNanos)
                    } == true
                )
                    ScaleState(evaluated.transition.target, null)
                else evaluated
        }
        highlights[key]?.let { highlight ->
            val evaluated = evaluateHighlight(highlight, now)
            highlights[key] =
                if (
                    evaluated.transition?.let {
                        elapsedAtLeast(now, it.startedNanos, it.durationNanos)
                    } == true
                )
                    HighlightState(evaluated.transition.target, null)
                else evaluated
        }
        syncTransition(key)
    }

    private fun syncTransition(key: ViewerElementKey) {
        if (scales[key]?.transition != null || highlights[key]?.transition != null)
            activeTransitions += key
        else activeTransitions -= key
    }

    private fun <T> clearPartOverrides(
        playerId: UUID,
        elementId: LocalId,
        overrides: MutableMap<ViewerElementKey, T>,
    ) {
        overrides.keys
            .filter { it.playerId == playerId && it.elementId == elementId && it.partId != null }
            .forEach { key ->
                overrides.remove(key)
                syncTransition(key)
            }
    }

    private fun keys(): Set<ViewerElementKey> = scales.keys + highlights.keys

    private fun evaluateScale(state: ScaleState, now: Long): ScaleState {
        val transition = state.transition ?: return state
        val current =
            if (elapsedAtLeast(now, transition.startedNanos, transition.durationNanos))
                transition.target
            else {
                val progress =
                    elapsedNanos(now, transition.startedNanos).toDouble() /
                        transition.durationNanos.toDouble()
                transition.start + (transition.target - transition.start) * progress
            }
        return ScaleState(current, transition.copy(current = current))
    }

    private fun evaluateHighlight(state: HighlightState, now: Long): HighlightState {
        val transition = state.transition ?: return state
        val current =
            if (elapsedAtLeast(now, transition.startedNanos, transition.durationNanos))
                transition.target
            else transition.start
        return HighlightState(current, transition.copy(current = current))
    }

    private companion object {
        val keyOrder =
            compareBy<ViewerElementKey>(
                { it.playerId.toString() },
                { it.elementId.value },
                { it.partId?.value.orEmpty() },
            )
    }
}
