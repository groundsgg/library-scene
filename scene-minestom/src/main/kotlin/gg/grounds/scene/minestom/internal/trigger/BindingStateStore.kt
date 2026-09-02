package gg.grounds.scene.minestom.internal.trigger

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.internal.elapsedLessThan
import java.util.UUID

internal class BindingStateStore {
    private data class State(
        var lastAcceptedNanos: Long? = null,
        var lastSucceededNanos: Long? = null,
        var inFlightGeneration: Long? = null,
    )

    private val states = mutableMapOf<BindingKey, State>()
    private var nextGeneration = 0L

    fun tryAccept(key: BindingKey, acceptedNanos: Long, debounceNanos: Long): Long? {
        val state = states.getOrPut(key) { State() }
        if (state.inFlightGeneration != null) return null
        if (
            state.lastAcceptedNanos?.let { elapsedLessThan(acceptedNanos, it, debounceNanos) } ==
                true
        )
            return null
        state.lastAcceptedNanos = acceptedNanos
        return (++nextGeneration).also { state.inFlightGeneration = it }
    }

    fun isCoolingDown(key: BindingKey, acceptedNanos: Long, cooldownNanos: Long): Boolean =
        states[key]?.lastSucceededNanos?.let {
            elapsedLessThan(acceptedNanos, it, cooldownNanos)
        } == true

    fun complete(key: BindingKey, generation: Long, succeeded: Boolean, completedNanos: Long) {
        val state = states[key] ?: return
        if (state.inFlightGeneration != generation) return
        state.inFlightGeneration = null
        if (succeeded) state.lastSucceededNanos = completedNanos
    }

    fun invalidatePlayer(playerId: UUID) {
        states
            .filterKeys { it.playerId == playerId }
            .forEach { (_, state) ->
                ++nextGeneration
                state.inFlightGeneration = null
            }
    }

    fun invalidateElement(elementId: LocalId) {
        states
            .filterKeys { it.npcId == elementId }
            .forEach { (_, state) ->
                ++nextGeneration
                state.inFlightGeneration = null
            }
    }

    fun clear() {
        states.clear()
        ++nextGeneration
    }
}
