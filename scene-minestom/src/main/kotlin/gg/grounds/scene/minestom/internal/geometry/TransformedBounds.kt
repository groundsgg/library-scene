package gg.grounds.scene.minestom.internal.geometry

import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Vec3

internal data class WorldBounds(val min: Vec3, val max: Vec3)

internal fun LocalBounds.transformed(transform: AffineTransform): WorldBounds {
    val half = Vec3(size.x / 2.0, size.y / 2.0, size.z / 2.0)
    val corners = listOf(-1.0, 1.0).flatMap { x ->
        listOf(-1.0, 1.0).flatMap { y ->
            listOf(-1.0, 1.0).map { z ->
                transform.transform(Vec3(center.x + x * half.x, center.y + y * half.y, center.z + z * half.z))
            }
        }
    }
    return WorldBounds(
        Vec3(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
        Vec3(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }),
    )
}
