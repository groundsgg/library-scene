package gg.grounds.scene.npc.minestom

import gg.grounds.npc.core.NpcModel
import gg.grounds.npc.minestom.*
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.*
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

internal class SceneNpcHandle(
    private val npc: NpcHandle,
    private val closeStart: () -> CompletionStage<Void>,
    private val model: NpcModel,
    private val lifetime: SceneRendererLifetime,
    private val registration: AutoCloseable,
    private val instance: Instance,
) : HeadLookRenderedAssetHandle, AsyncRenderedAssetHandle {
    override val headLookOrigin
        get() = npc.snapshot().lookOrigin?.let(SceneNpcTransformMapper::toScene)

    private fun observe(stage: CompletionStage<Void>, player: Player? = null) {
        stage.whenComplete { _, error ->
            if (
                error != null && (player == null || player.isOnline && player.instance === instance)
            )
                lifetime.reportFailure((error as? CompletionException)?.cause ?: error)
        }
    }

    override fun applyTransform(transform: SceneRenderTransform) {
        observe(npc.applyTransform(SceneNpcTransformMapper.toNpc(transform)))
    }

    override fun applyViewerState(player: Player, state: SceneViewerVisualState) {
        observe(
            npc.applyViewerState(player, NpcViewerState(state.scaleMultiplier, state.highlighted)),
            player,
        )
    }

    override fun clearViewerState(player: Player) {
        observe(npc.clearViewerState(player))
    }

    override fun startAnimation(animation: LocalId, elapsedMillis: Long) {
        require(animation.value in model.animations) { "Unknown NPC clip ${animation.value}" }
        require(elapsedMillis >= 0)
        observe(npc.startAnimation(animation.value, elapsedMillis))
    }

    override fun stopAnimation(animation: LocalId?) {
        observe(npc.stopAnimation(animation?.value))
    }

    override fun advanceAnimation(elapsedMillis: Long) {
        observe(npc.advanceAnimation(elapsedMillis))
    }

    override fun applyHeadLook(yawDegrees: Double, pitchDegrees: Double) {
        observe(npc.applyHeadLook(yawDegrees, pitchDegrees))
    }

    override fun close() {
        closeAsync()
    }

    override fun closeAsync(): CompletionStage<Void> {
        registration.close()
        return closeStart()
    }
}
