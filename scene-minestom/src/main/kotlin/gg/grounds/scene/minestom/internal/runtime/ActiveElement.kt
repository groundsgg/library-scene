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
        npcEntities?.close()
        handles.asReversed().forEach(RenderedAssetHandle::close)
    }
}
