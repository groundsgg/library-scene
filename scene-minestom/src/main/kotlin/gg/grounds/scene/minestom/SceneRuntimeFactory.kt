package gg.grounds.scene.minestom

import gg.grounds.scene.minestom.internal.DefaultSceneRuntime
import gg.grounds.scene.minestom.internal.SceneReadiness
import gg.grounds.scene.minestom.internal.SceneReadinessRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

object SceneRuntimeFactory {
    fun create(request: SceneRuntimeRequest): CompletionStage<SceneRuntimeCreationResult> {
        val readiness =
            try {
                SceneReadiness.prepare(
                    SceneReadinessRequest(
                        scene = request.scene,
                        assets = request.assets,
                        actions = request.actions,
                        renderers = request.renderers,
                        effects = request.effects,
                        actionRegistry = request.actionRegistry,
                        identity = request.identity,
                        config = request.config,
                    )
                )
            } catch (_: Throwable) {
                return CompletableFuture.completedFuture(
                    SceneRuntimeCreationResult.Failure(
                        listOf(
                            SceneRuntimeProblem(
                                SceneRuntimeProblemCode.RUNTIME_FAILURE,
                                "runtime",
                                null,
                                "Runtime readiness check failed.",
                            )
                        )
                    )
                )
            }
        if (readiness.problems.isNotEmpty()) {
            return CompletableFuture.completedFuture(
                SceneRuntimeCreationResult.Failure(readiness.problems)
            )
        }
        return try {
            DefaultSceneRuntime.create(request, checkNotNull(readiness.capabilities))
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
