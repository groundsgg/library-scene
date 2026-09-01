package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.geometry.affine
import kotlin.math.max
import kotlin.math.min

internal class BoundsRaycaster {
    fun rayDistance(npc: Npc, eye: Vec3, direction: Vec3, maximumDistance: Double): Double? {
        val inverse = SceneRenderTransform(npc.transform, null).affine().inverse()
        val localEye = inverse.transform(eye)
        val localDirection = inverse.transformVector(direction)
        val half = npc.interactionBounds.size.let { Vec3(it.x / 2.0, it.y / 2.0, it.z / 2.0) }
        val minimum =
            npc.interactionBounds.center.let { Vec3(it.x - half.x, it.y - half.y, it.z - half.z) }
        val maximum =
            npc.interactionBounds.center.let { Vec3(it.x + half.x, it.y + half.y, it.z + half.z) }
        var enter = Double.NEGATIVE_INFINITY
        var exit = Double.POSITIVE_INFINITY
        listOf(
                Triple(localEye.x, localDirection.x, minimum.x to maximum.x),
                Triple(localEye.y, localDirection.y, minimum.y to maximum.y),
                Triple(localEye.z, localDirection.z, minimum.z to maximum.z),
            )
            .forEach { (origin, delta, bounds) ->
                if (delta == 0.0) {
                    if (origin < bounds.first || origin > bounds.second) return null
                } else {
                    val near = (bounds.first - origin) / delta
                    val far = (bounds.second - origin) / delta
                    enter = max(enter, min(near, far))
                    exit = min(exit, max(near, far))
                }
            }
        val distance = max(0.0, enter)
        return distance.takeIf { exit >= distance && distance <= maximumDistance }
    }
}
