package gg.grounds.scene.minestom.internal.view

import kotlin.math.ceil
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.EntityTracker
import net.minestom.server.instance.Instance

internal class MinestomSensorQueries(
    private val instance: Instance,
    private val interactionReach: Double = 5.0,
    private val normalHoverExtent: Double = 16.0,
) {
    private val hoverChunkRange = ceil((interactionReach + normalHoverExtent) / 16.0).toInt()

    fun hoverInteractions(eye: Point): List<Entity> =
        buildList {
                instance.getEntityTracker().nearbyEntitiesByChunkRange(
                    eye,
                    hoverChunkRange,
                    EntityTracker.Target.ENTITIES,
                ) { entity ->
                    add(entity)
                }
            }
            .sortedBy(Entity::getUuid)

    fun proximityChunkRange(radius: Double): Int? {
        val range = ceil(radius / 16.0)
        if (!range.isFinite() || range < 0.0 || range > MAX_PROXIMITY_CHUNK_RANGE) return null
        return range.toInt()
    }

    fun proximityPlayers(point: Point, radius: Double): List<Player>? {
        val range = proximityChunkRange(radius) ?: return null
        return buildList {
                instance.getEntityTracker().nearbyEntitiesByChunkRange(
                    point,
                    range,
                    EntityTracker.Target.PLAYERS,
                ) { player ->
                    add(player)
                }
            }
            .sortedBy(Player::getUuid)
    }

    private companion object {
        const val MAX_PROXIMITY_CHUNK_RANGE = 8
    }
}
