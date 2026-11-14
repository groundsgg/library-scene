package gg.grounds.scene.npc.minestom

import gg.grounds.npc.core.NpcModel
import gg.grounds.npc.minestom.*
import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

/** Models are decoded and catalogue-validated by the host before registration. */
fun interface SceneNpcModelSource {
    fun resolve(assetKey: String): NpcModel?
}

class SceneNpcRenderers
@JvmOverloads
constructor(
    private val models: SceneNpcModelSource,
    private val meshes: NpcMeshResolver? = null,
    private val sounds: NpcSoundSink? = null,
    private val config: NpcRenderConfig = NpcRenderConfig(),
) : SceneAssetRendererRegistry {
    override fun rendererFor(asset: AssetKey, kind: AssetKind): SceneAssetRendererFactory? {
        if (kind != AssetKind.NPC_BODY) return null
        val model = models.resolve(asset.value) ?: return null
        require(model.id == asset.value) { "NPC model source returned a different asset" }
        return Factory(model)
    }

    fun supportsAnimation(asset: AssetKey, animation: LocalId?): Boolean =
        models.resolve(asset.value)?.let { animation == null || animation.value in it.animations }
            ?: false

    fun supportsViewerScale(asset: AssetKey): Boolean = models.resolve(asset.value) != null

    private inner class Factory(private val model: NpcModel) : ScopedSceneAssetRendererFactory {
        override fun create(
            context: SceneAssetRenderContext
        ): CompletionStage<RenderedAssetHandle> =
            CompletableFuture.failedFuture(
                IllegalStateException("NPC renderer requires Scene's scoped factory capability")
            )

        override fun createScoped(
            context: SceneAssetRenderContext,
            lifetime: SceneRendererLifetime,
        ): CompletionStage<RenderedAssetHandle> {
            require(context.asset.value == model.id)
            val defaults =
                NpcSpawnRequest(
                    context.instance,
                    model,
                    SceneNpcTransformMapper.toNpc(context.transform),
                    NpcPublicationGuard(lifetime::runIfCurrent),
                )
            val start =
                NpcRenderer.prepare(
                    defaults.copy(
                        meshes = meshes ?: defaults.meshes,
                        sounds = sounds ?: defaults.sounds,
                        config = config,
                    )
                )
            val cleanup = CompletableFuture<Void>()
            lifetime.trackCleanup(cleanup)
            fun closeStart(): CompletionStage<Void> {
                try {
                    start.cancel().whenComplete { _, error ->
                        if (error == null) cleanup.complete(null)
                        else cleanup.completeExceptionally(error)
                    }
                } catch (error: Throwable) {
                    cleanup.completeExceptionally(error)
                }
                return cleanup.minimalCompletionStage()
            }
            val registration = lifetime.onInvalidated(Runnable { closeStart() })
            val result = CompletableFuture<RenderedAssetHandle>()
            start
                .ready()
                .thenCompose { prepared ->
                    when (prepared) {
                        is NpcPrepareResult.Ready ->
                            prepared.handle.publish().thenApply<RenderedAssetHandle> {
                                SceneNpcHandle(
                                    prepared.handle,
                                    ::closeStart,
                                    model,
                                    lifetime,
                                    registration,
                                    context.instance,
                                )
                            }
                        is NpcPrepareResult.Rejected ->
                            CompletableFuture.failedFuture(
                                IllegalStateException(prepared.problems.joinToString { it.message })
                            )
                    }
                }
                .whenComplete { handle, error ->
                    if (error == null && lifetime.isCurrent()) result.complete(handle)
                    else {
                        val failure =
                            (error as? CompletionException)?.cause
                                ?: error
                                ?: CancellationException("Scene NPC generation changed")
                        closeStart().whenComplete { _, cleanupError ->
                            registration.close()
                            if (cleanupError != null && cleanupError !== failure)
                                failure.addSuppressed(cleanupError)
                            result.completeExceptionally(failure)
                        }
                    }
                }
            return result.minimalCompletionStage()
        }
    }
}
