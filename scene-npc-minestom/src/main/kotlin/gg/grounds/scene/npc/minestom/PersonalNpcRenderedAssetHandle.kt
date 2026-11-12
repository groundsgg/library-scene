package gg.grounds.scene.npc.minestom

import gg.grounds.npc.minestom.NpcPresentationOptions
import gg.grounds.npc.minestom.NpcPresentationStart
import gg.grounds.scene.minestom.RenderedAssetHandle

/** Optional capability of a concrete, generation-bound NPC renderer handle. */
interface PersonalNpcRenderedAssetHandle : RenderedAssetHandle {
    fun preparePresentation(options: NpcPresentationOptions): NpcPresentationStart
}
