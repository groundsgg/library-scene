package gg.grounds.scene.interaction

import gg.grounds.scene.format.*
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Ray against the transformed local NPC bounds; returned distance is in world units. */
object SceneBoundsRaycaster {
    fun distance(
        bounds: LocalBounds,
        root: Transform,
        local: Transform?,
        eye: Vec3,
        direction: Vec3,
        maximumDistance: Double,
    ): Double? {
        require(maximumDistance.isFinite() && maximumDistance > 0.0)
        val length = hypot(hypot(direction.x, direction.y), direction.z)
        require(length.isFinite() && length > 0.0)
        val normalized = Vec3(direction.x / length, direction.y / length, direction.z / length)
        val transform =
            AffineTransform.from(root)
                .let { matrix -> local?.let { matrix * AffineTransform.from(it) } ?: matrix }
                .inverse()
        val origin = transform.transform(eye)
        val delta = transform.transformVector(normalized)
        var enter = Double.NEGATIVE_INFINITY
        var exit = Double.POSITIVE_INFINITY
        listOf(
                Triple(origin.x, delta.x, bounds.center.x to bounds.size.x),
                Triple(origin.y, delta.y, bounds.center.y to bounds.size.y),
                Triple(origin.z, delta.z, bounds.center.z to bounds.size.z),
            )
            .forEach { (o, d, bound) ->
                val low = bound.first - bound.second / 2
                val high = bound.first + bound.second / 2
                if (d == 0.0) {
                    if (o < low || o > high) return null
                } else {
                    val a = (low - o) / d
                    val b = (high - o) / d
                    enter = max(enter, min(a, b))
                    exit = min(exit, max(a, b))
                }
            }
        val hit = max(0.0, enter)
        return hit.takeIf { exit >= hit && hit <= maximumDistance }
    }
}

data class SceneHoverHit(val elementId: LocalId, val distance: Double) {
    init {
        require(distance.isFinite() && distance >= 0.0)
    }
}

data class SceneHoverTransition(
    val playerId: UUID,
    val elementId: LocalId,
    val trigger: SceneTrigger,
)

/** Caller supplies only currently visible and eligible candidates. */
class SceneHoverSelector {
    private val hovered = mutableMapOf<UUID, LocalId>()

    fun current(playerId: UUID): LocalId? = hovered[playerId]

    fun update(playerId: UUID, hits: Iterable<SceneHoverHit>): List<SceneHoverTransition> {
        val selected =
            hits
                .minWithOrNull(compareBy<SceneHoverHit>({ it.distance }, { it.elementId.value }))
                ?.elementId
        val old = hovered[playerId]
        if (old == selected) return emptyList()
        if (selected == null) hovered.remove(playerId) else hovered[playerId] = selected
        return buildList {
            old?.let { add(SceneHoverTransition(playerId, it, SceneTrigger.HOVER_LEAVE)) }
            selected?.let { add(SceneHoverTransition(playerId, it, SceneTrigger.HOVER_ENTER)) }
        }
    }

    fun removeViewer(playerId: UUID): List<SceneHoverTransition> =
        hovered.remove(playerId)?.let {
            listOf(SceneHoverTransition(playerId, it, SceneTrigger.HOVER_LEAVE))
        } ?: emptyList()

    fun forgetElement(elementId: LocalId) {
        hovered.entries.removeIf { it.value == elementId }
    }

    fun clear() {
        hovered.clear()
    }
}
