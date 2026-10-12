package gg.grounds.scene.minestom.internal.collision

import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.NpcCollisionMode
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.geometry.WorldBounds
import gg.grounds.scene.minestom.internal.geometry.affine
import gg.grounds.scene.minestom.internal.geometry.transformed
import kotlin.math.sqrt

internal data class NpcCollisionGeometry(val bounds: WorldBounds, val radius: Double)

internal fun Npc.collisionGeometry(transform: SceneRenderTransform): NpcCollisionGeometry {
    val bounds = interactionBounds.transformed(transform.affine())
    val halfX = (bounds.max.x - bounds.min.x) / 2.0
    val height = bounds.max.y - bounds.min.y
    val halfZ = (bounds.max.z - bounds.min.z) / 2.0
    return NpcCollisionGeometry(bounds, sqrt(halfX * halfX + height * height + halfZ * halfZ))
}

internal fun Npc.requireSupportedCollisionGeometry(
    transform: SceneRenderTransform
): NpcCollisionGeometry =
    collisionGeometry(transform).also { geometry ->
        require(
            collision.mode == NpcCollisionMode.NONE ||
                (geometry.radius.isFinite() && geometry.radius <= MAX_NPC_COLLIDER_RADIUS)
        ) {
            "NPC collision bounds require a finite transformed radius at most " +
                "$MAX_NPC_COLLIDER_RADIUS blocks."
        }
    }

internal const val MAX_NPC_COLLIDER_RADIUS = 64.0
internal const val MAX_NPC_QUERY_RADIUS = 112.0
