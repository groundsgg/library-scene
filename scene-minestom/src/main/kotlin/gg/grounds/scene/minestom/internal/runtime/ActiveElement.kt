package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Npc
import gg.grounds.scene.minestom.RenderedAssetHandle
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.geometry.WorldBounds
import gg.grounds.scene.minestom.internal.geometry.affine
import gg.grounds.scene.minestom.internal.geometry.transformed
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.metadata.other.InteractionMeta

internal class ActiveElement(
    val elementId: LocalId,
    val generation: Long,
    val handles: List<RenderedAssetHandle>,
    val npcEntities: NpcPlatformEntities?,
    val npc: Npc? = null,
    private val handleTransforms: List<SceneRenderTransform> =
        npc?.let { authored ->
                List(handles.size) { SceneRenderTransform(authored.transform, null) }
            }
            .orEmpty(),
    private val handlePartIds: List<LocalId?> = List(handles.size) { null },
    initialTransform: SceneRenderTransform? = npc?.let { SceneRenderTransform(it.transform, null) },
    private val beginResourceOperation: () -> Unit = {},
    private val completeResourceOperation: (Throwable?) -> Unit = { _ -> },
    private val runtimeClosing: () -> Boolean = { false },
    private val schedule: (Runnable) -> Unit = Runnable::run,
) : AutoCloseable {
    private var runtimeTransform: SceneRenderTransform? = initialTransform
    private val closed = AtomicBoolean()

    val currentTransform: SceneRenderTransform
        get() = checkNotNull(runtimeTransform) { "Active element has no runtime transform." }

    fun transformOr(authored: SceneRenderTransform): SceneRenderTransform =
        runtimeTransform ?: authored

    fun currentBounds(): WorldBounds =
        npc?.interactionBounds?.transformed(currentTransform.affine())
            ?: error("Only NPC elements have interaction bounds.")

    fun handlesFor(partId: LocalId?): List<RenderedAssetHandle> =
        if (partId == null) handles
        else handles.filterIndexed { index, _ -> handlePartIds.getOrNull(index) == partId }

    fun handleEntries(): List<Pair<LocalId?, RenderedAssetHandle>> =
        handles.mapIndexed { index, handle -> handlePartIds.getOrNull(index) to handle }

    fun updateRuntimeTransform(transform: SceneRenderTransform) {
        runtimeTransform = transform
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                val current = failure
                if (current == null) failure = error
                else if (current !== error) current.addSuppressed(error)
            }
        }
        handles.forEachIndexed { index, handle ->
            val local = handleTransforms.getOrNull(index)?.local
            attempt { handle.applyTransform(SceneRenderTransform(transform.root, local)) }
        }
        val authoredNpc = npc
        val entities = npcEntities
        if (authoredNpc != null && entities != null) {
            val affine = transform.affine()
            val bounds = authoredNpc.interactionBounds.transformed(affine)
            attempt {
                (entities.interaction.entityMeta as InteractionMeta).apply {
                    width = max(bounds.max.x - bounds.min.x, bounds.max.z - bounds.min.z).toFloat()
                    height = (bounds.max.y - bounds.min.y).toFloat()
                }
            }
            val centerX = (bounds.min.x + bounds.max.x) / 2.0
            val centerZ = (bounds.min.z + bounds.max.z) / 2.0
            if (entities.interaction.instance != null) {
                attempt {
                    observeNpcMove(
                        entities.interaction.teleport(
                            Pos(
                                centerX,
                                bounds.min.y,
                                centerZ,
                                transform.root.rotation.yaw.toFloat(),
                                transform.root.rotation.pitch.toFloat(),
                            )
                        )
                    )
                }
            }
            entities.label
                ?.takeIf { it.instance != null }
                ?.let { label ->
                    val point = affine.transform(authoredNpc.labelOffset)
                    attempt {
                        observeNpcMove(
                            label.teleport(
                                Pos(
                                    point.x,
                                    point.y,
                                    point.z,
                                    transform.root.rotation.yaw.toFloat(),
                                    transform.root.rotation.pitch.toFloat(),
                                )
                            )
                        )
                    }
                }
        }
        failure?.let { throw it }
    }

    private fun observeNpcMove(stage: CompletionStage<Void>) {
        beginResourceOperation()
        try {
            stage.whenComplete { _, stageError ->
                try {
                    schedule(
                        Runnable {
                            val cleanupError =
                                if (closed.get() || runtimeClosing()) {
                                    closeOwnedResources(npcEntities, emptyList())
                                } else null
                            completeResourceOperation(stageError.withSuppressed(cleanupError))
                        }
                    )
                } catch (scheduleError: Throwable) {
                    completeResourceOperation(stageError.withSuppressed(scheduleError))
                }
            }
        } catch (error: Throwable) {
            completeResourceOperation(error)
            throw error
        }
    }

    override fun close() {
        closed.set(true)
        closeOwnedResources(npcEntities, handles)?.let { throw it }
    }
}

private fun Throwable?.withSuppressed(secondary: Throwable?): Throwable? {
    if (secondary == null) return this
    val primary = this ?: return secondary
    if (secondary !== primary) primary.addSuppressed(secondary)
    return primary
}

internal fun closeOwnedResources(
    npcEntities: NpcPlatformEntities?,
    handles: List<RenderedAssetHandle>,
    primaryFailure: Throwable? = null,
): Throwable? {
    var failure = primaryFailure

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            val current = failure
            if (current == null) failure = error
            else if (error !== current) current.addSuppressed(error)
        }
    }

    npcEntities?.let { attempt(it::close) }
    handles.asReversed().forEach { handle -> attempt(handle::close) }
    return failure
}
