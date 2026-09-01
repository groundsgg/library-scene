package gg.grounds.scene.minestom

import gg.grounds.scene.format.*
import java.util.concurrent.CompletionStage
import net.minestom.server.entity.Player

fun interface SceneActionHandler {
    fun execute(context: SceneActionContext): CompletionStage<SceneActionResult>
}

fun interface SceneActionRegistry {
    fun handlerFor(key: ActionKey): SceneActionHandler?
}

data class SceneActionContext(
    val identity: SceneRuntimeIdentity,
    val player: Player,
    val elementId: LocalId,
    val trigger: SceneTrigger,
    val hand: SceneHand?,
    val acceptedNanos: Long,
    val arguments: Map<LocalId, ApplicationArgument>,
    val viewerState: SceneViewerVisualState,
)

sealed interface SceneActionResult {
    data object Success : SceneActionResult

    data class Rejected(val diagnostic: String) : SceneActionResult

    data class Failure(val diagnostic: String, val cause: Throwable? = null) : SceneActionResult
}
