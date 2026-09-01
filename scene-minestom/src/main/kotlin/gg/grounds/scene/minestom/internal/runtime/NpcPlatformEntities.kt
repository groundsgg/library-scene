package gg.grounds.scene.minestom.internal.runtime

import net.minestom.server.entity.Entity

internal data class NpcPlatformEntities(val label: Entity?, val interaction: Entity) :
    AutoCloseable {
    override fun close() {
        var failure: Throwable? = null
        listOfNotNull(label, interaction).forEach { entity ->
            try {
                entity.remove()
            } catch (error: Throwable) {
                val current = failure
                if (current == null) failure = error
                else if (error !== current) current.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
