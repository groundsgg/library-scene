package gg.grounds.scene.minestom

import gg.grounds.scene.format.LocalId

enum class SceneRuntimeProblemCode {
    INVALID_SCENE,
    INVALID_CONFIG,
    MISSING_RENDERER,
    MISSING_EFFECT,
    MISSING_ACTION_HANDLER,
    ACTIVATION_FAILED,
    RUNTIME_FAILURE,
}

data class SceneRuntimeProblem(
    val code: SceneRuntimeProblemCode,
    val path: String,
    val elementId: LocalId?,
    val message: String,
) {
    val elementIdValue: String?
        get() = elementId?.value
}
