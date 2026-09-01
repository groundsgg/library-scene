package gg.grounds.scene.minestom.internal.spatial

import gg.grounds.scene.format.ActivationPolicy
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Vec3
import kotlin.math.floor
import net.minestom.server.coordinate.Point

internal data class CellKey(val x: Int, val z: Int)

internal data class IndexedElement(
    val id: LocalId,
    val policy: ActivationPolicy,
    val position: Vec3,
)

internal class SpatialIndex(elements: List<IndexedElement>, private val cellEdge: Double) {
    private val elementsById = elements.associateBy { it.id }
    private val cells =
        elements
            .groupBy { cellFor(it.position.x, it.position.z) }
            .mapValues { (_, value) -> value.sortedBy { it.id.value } }
    private var lastVisitedElementCount = 0

    fun candidates(point: Point, radius: Double): List<LocalId> {
        val minimumX = cellFor(point.x() - radius, point.z() - radius).x
        val maximumX = cellFor(point.x() + radius, point.z() + radius).x
        val minimumZ = cellFor(point.x() - radius, point.z() - radius).z
        val maximumZ = cellFor(point.x() + radius, point.z() + radius).z
        val candidates =
            buildList {
                    for (x in minimumX..maximumX) for (z in minimumZ..maximumZ) addAll(
                        cells[CellKey(x, z)].orEmpty()
                    )
                }
                .sortedWith(
                    compareBy<IndexedElement>(
                        { cellFor(it.position.x, it.position.z).x },
                        { cellFor(it.position.x, it.position.z).z },
                        { it.id.value },
                    )
                )
        lastVisitedElementCount = candidates.size
        return candidates
            .filter { distanceSquared(it.position, point) <= radius * radius }
            .map { it.id }
    }

    fun visitedElementCount(): Int = lastVisitedElementCount

    internal fun element(id: LocalId): IndexedElement = elementsById.getValue(id)

    internal fun cellKey(id: LocalId): CellKey =
        element(id).let { cellFor(it.position.x, it.position.z) }

    internal fun elements(): List<IndexedElement> =
        elementsById.values.sortedWith(
            compareBy(
                { cellFor(it.position.x, it.position.z).x },
                { cellFor(it.position.x, it.position.z).z },
                { it.id.value },
            )
        )

    private fun cellFor(x: Double, z: Double) =
        CellKey(floor(x / cellEdge).toInt(), floor(z / cellEdge).toInt())

    private fun distanceSquared(position: Vec3, point: Point): Double =
        (position.x - point.x()) * (position.x - point.x()) +
            (position.z - point.z()) * (position.z - point.z())
}
