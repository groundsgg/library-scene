package gg.grounds.scene.minestom.internal.runtime

import net.minestom.server.entity.Entity

internal data class NpcPlatformEntities(val label: Entity?, val interaction: Entity) :
    AutoCloseable {
    override fun close() {
        label?.remove()
        interaction.remove()
    }
}
