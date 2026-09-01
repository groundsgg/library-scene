package gg.grounds.scene.minestom

import gg.grounds.scene.format.*
import java.util.concurrent.CompletionStage
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

data class SceneRenderTransform(val root: Transform, val local: Transform?)
data class SceneViewerVisualState(val scaleMultiplier: Double = 1.0, val highlighted: Boolean = false)

data class SceneAssetRenderContext(
    val instance: Instance,
    val elementId: LocalId,
    val partId: LocalId?,
    val asset: AssetKey,
    val transform: SceneRenderTransform,
)

fun interface SceneAssetRendererFactory {
    fun create(context: SceneAssetRenderContext): CompletionStage<RenderedAssetHandle>
}

fun interface SceneAssetRendererRegistry {
    fun rendererFor(asset: AssetKey, kind: AssetKind): SceneAssetRendererFactory?
}

interface RenderedAssetHandle : AutoCloseable {
    fun applyTransform(transform: SceneRenderTransform)
    fun applyViewerState(player: Player, state: SceneViewerVisualState)
    fun clearViewerState(player: Player)
    fun startAnimation(animation: LocalId, elapsedMillis: Long)
    fun stopAnimation(animation: LocalId?)
    fun advanceAnimation(elapsedMillis: Long)
    override fun close()
}
