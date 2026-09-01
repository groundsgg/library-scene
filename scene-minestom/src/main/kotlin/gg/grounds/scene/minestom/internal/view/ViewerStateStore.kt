package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.SceneViewerVisualState
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import java.util.UUID
import net.minestom.server.instance.Instance

internal data class ViewerElementKey(val playerId: UUID, val elementId: LocalId)

internal class ViewerStateStore {
    private val states = mutableMapOf<ViewerElementKey, SceneViewerVisualState>()

    fun visualState(key: ViewerElementKey): SceneViewerVisualState =
        states[key] ?: SceneViewerVisualState()

    fun setScale(key: ViewerElementKey, multiplier: Double): SceneViewerVisualState =
        visualState(key).copy(scaleMultiplier = multiplier).also { states[key] = it }

    fun setHighlight(key: ViewerElementKey, enabled: Boolean): SceneViewerVisualState =
        visualState(key).copy(highlighted = enabled).also { states[key] = it }

    fun removePlayer(playerId: UUID) {
        states.keys.removeIf { it.playerId == playerId }
    }

    fun apply(instance: Instance, active: ActiveElement, key: ViewerElementKey) {
        val player = instance.getPlayerByUuid(key.playerId) ?: return
        active.handles.forEach { it.applyViewerState(player, visualState(key)) }
    }

    fun clear(instance: Instance, active: ActiveElement, key: ViewerElementKey) {
        val player = instance.getPlayerByUuid(key.playerId) ?: return
        active.handles.forEach { it.clearViewerState(player) }
    }

    fun clear() = states.clear()
}
