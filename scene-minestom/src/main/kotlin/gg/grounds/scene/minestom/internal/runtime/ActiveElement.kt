package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Npc
import gg.grounds.scene.minestom.RenderedAssetHandle
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.geometry.affine
import gg.grounds.scene.minestom.internal.geometry.transformed
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
) : AutoCloseable {
    private var runtimeTransform: SceneRenderTransform? = initialTransform

    val currentTransform: SceneRenderTransform
        get() = checkNotNull(runtimeTransform) { "Active element has no runtime transform." }

    fun transformOr(authored: SceneRenderTransform): SceneRenderTransform =
        runtimeTransform ?: authored

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
                    entities.interaction.teleport(
                        Pos(
                            centerX,
                            bounds.min.y,
                            centerZ,
                            transform.root.rotation.yaw.toFloat(),
                            transform.root.rotation.pitch.toFloat(),
                        )
                    )
                }
            }
            entities.label
                ?.takeIf { it.instance != null }
                ?.let { label ->
                    val point = affine.transform(authoredNpc.labelOffset)
                    attempt {
                        label.teleport(
                            Pos(
                                point.x,
                                point.y,
                                point.z,
                                transform.root.rotation.yaw.toFloat(),
                                transform.root.rotation.pitch.toFloat(),
                            )
                        )
                    }
                }
        }
        failure?.let { throw it }
    }

    override fun close() {
        closeOwnedResources(npcEntities, handles)?.let { throw it }
    }
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
