package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Npc
import gg.grounds.scene.minestom.RenderedAssetHandle

internal data class ActiveElement(
    val elementId: LocalId,
    val generation: Long,
    val handles: List<RenderedAssetHandle>,
    val npcEntities: NpcPlatformEntities?,
    val npc: Npc? = null,
) : AutoCloseable {
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
