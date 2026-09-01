package gg.grounds.scene.minestom

import gg.grounds.scene.minestom.internal.DefaultSceneRuntime
import gg.grounds.scene.minestom.internal.SceneReadiness
import gg.grounds.scene.minestom.internal.SceneReadinessRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

object SceneRuntimeFactory {
    fun create(request: SceneRuntimeRequest): CompletionStage<SceneRuntimeCreationResult> {
        val problems =
            SceneReadiness.check(
                SceneReadinessRequest(
                    scene = request.scene,
                    assets = request.assets,
                    actions = request.actions,
                    renderers = request.renderers,
                    effects = request.effects,
                    actionRegistry = request.actionRegistry,
                    config = request.config,
                )
            )
        if (problems.isNotEmpty()) {
            return CompletableFuture.completedFuture(SceneRuntimeCreationResult.Failure(problems))
        }
        return try {
            DefaultSceneRuntime.create(request)
        } catch (_: Throwable) {
            CompletableFuture.completedFuture(
                SceneRuntimeCreationResult.Failure(
                    listOf(
                        SceneRuntimeProblem(
                            SceneRuntimeProblemCode.RUNTIME_FAILURE,
                            "runtime",
                            null,
                            "Runtime preparation failed.",
                        )
                    )
                )
            )
        }
    }
}
