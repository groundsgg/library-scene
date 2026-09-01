package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneElement

internal data class LogicalAnimationState(val animation: LocalId?, val startedNanos: Long?)

internal data class LogicalElementState(
    val element: SceneElement,
    var animation: LogicalAnimationState,
    var generation: Long,
)
