package gg.grounds.scene.minestom

import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.SceneDocument
import java.util.concurrent.CompletionStage
import net.minestom.server.instance.Instance

data class SceneRuntimeRequest(
    val scene: SceneDocument,
    val assets: AssetCatalog,
    val actions: ActionCatalog,
    val identity: SceneRuntimeIdentity,
    val instance: Instance,
    val renderers: SceneAssetRendererRegistry,
    val effects: SceneEffectSink,
    val playerPolicy: ScenePlayerPolicy,
    val actionRegistry: SceneActionRegistry,
    val clock: SceneClock = SceneClock { System.nanoTime() },
    val config: SceneRuntimeConfig = SceneRuntimeConfig(),
)

sealed interface SceneRuntimeCreationResult {
    data class Success(val runtime: SceneRuntime) : SceneRuntimeCreationResult

    data class Failure(val problems: List<SceneRuntimeProblem>) : SceneRuntimeCreationResult {
        init {
            require(problems.isNotEmpty()) { "Runtime creation failures require a problem." }
        }
    }
}

interface SceneRuntime {
    val identity: SceneRuntimeIdentity
    val isClosed: Boolean

    fun close(): CompletionStage<Void>
}
