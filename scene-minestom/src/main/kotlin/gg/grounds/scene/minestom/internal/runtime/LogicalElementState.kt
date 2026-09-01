package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneElement

internal data class LogicalAnimationState(val animation: LocalId?, val startedNanos: Long?)

internal class LogicalElementState(
    val element: SceneElement,
    animation: LogicalAnimationState,
    var generation: Long,
) {
    private val animations = mutableMapOf<LocalId?, LogicalAnimationState>(null to animation)

    var animation: LogicalAnimationState
        get() = animations.getValue(null)
        set(value) {
            animations[null] = value
        }

    fun animationFor(partId: LocalId?): LogicalAnimationState =
        animations[partId] ?: animations.getValue(null)

    fun setAnimation(partId: LocalId?, animation: LogicalAnimationState) {
        animations[partId] = animation
    }
}
