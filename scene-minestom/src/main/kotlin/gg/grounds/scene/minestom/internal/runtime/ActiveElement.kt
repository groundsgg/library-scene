package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.RenderedAssetHandle

internal data class ActiveElement(
    val elementId: LocalId,
    val generation: Long,
    val handles: List<RenderedAssetHandle>,
    val npcEntities: NpcPlatformEntities?,
) : AutoCloseable {
    override fun close() {
        npcEntities?.close()
        handles.asReversed().forEach(RenderedAssetHandle::close)
    }
}
