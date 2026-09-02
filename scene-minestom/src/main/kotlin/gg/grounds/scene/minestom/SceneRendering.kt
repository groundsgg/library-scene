package gg.grounds.scene.minestom

import gg.grounds.scene.format.*
import java.util.concurrent.CompletionStage
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

data class SceneRenderTransform(val root: Transform, val local: Transform?)

/** Monotonic transition snapshot supplied to a renderer for one viewer and resolved target. */
data class SceneViewerScaleTransition(
    val startedNanos: Long,
    val durationNanos: Long,
    val start: Double,
    val target: Double,
    val current: Double,
)

/** Monotonic transition snapshot supplied to a renderer for one viewer and resolved target. */
data class SceneViewerHighlightTransition(
    val startedNanos: Long,
    val durationNanos: Long,
    val start: Boolean,
    val target: Boolean,
    val current: Boolean,
)

/** Current viewer-specific values plus any in-progress transition metadata. */
data class SceneViewerVisualState(
    val scaleMultiplier: Double = 1.0,
    val highlighted: Boolean = false,
    val scaleTransition: SceneViewerScaleTransition? = null,
    val highlightTransition: SceneViewerHighlightTransition? = null,
)

data class SceneAssetRenderContext(
    val instance: Instance,
    val elementId: LocalId,
    val partId: LocalId?,
    val asset: AssetKey,
    val transform: SceneRenderTransform,
) {
    val elementIdValue: String
        get() = elementId.value

    val partIdValue: String?
        get() = partId?.value

    val assetValue: String
        get() = asset.value
}

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
