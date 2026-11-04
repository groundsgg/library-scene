package gg.grounds.scene.interaction

import gg.grounds.scene.format.*
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Ray against the transformed local NPC bounds; returned distance is in world units. */
object SceneBoundsRaycaster {
    /** Immutable geometry preparation, reusable across viewer rays until the transform changes. */
    fun prepare(bounds: LocalBounds, root: Transform, local: Transform?): PreparedBounds =
        PreparedBounds(
            bounds,
            AffineTransform.from(root)
                .let { matrix -> local?.let { matrix * AffineTransform.from(it) } ?: matrix }
                .inverse(),
        )

    fun distance(
        bounds: LocalBounds,
        root: Transform,
        local: Transform?,
        eye: Vec3,
        direction: Vec3,
        maximumDistance: Double,
    ): Double? = prepare(bounds, root, local).distance(eye, direction, maximumDistance)

    class PreparedBounds
    internal constructor(private val bounds: LocalBounds, private val inverse: AffineTransform) {
        fun distance(eye: Vec3, direction: Vec3, maximumDistance: Double): Double? {
            require(maximumDistance.isFinite() && maximumDistance > 0.0)
            val length = hypot(hypot(direction.x, direction.y), direction.z)
            require(length.isFinite() && length > 0.0)
            val normalized = Vec3(direction.x / length, direction.y / length, direction.z / length)
            val origin = inverse.transform(eye)
            val delta = inverse.transformVector(normalized)
            var enter = Double.NEGATIVE_INFINITY
            var exit = Double.POSITIVE_INFINITY
            for (axis in 0..2) {
                val o: Double
                val d: Double
                val center: Double
                val size: Double
                when (axis) {
                    0 -> {
                        o = origin.x
                        d = delta.x
                        center = bounds.center.x
                        size = bounds.size.x
                    }

                    1 -> {
                        o = origin.y
                        d = delta.y
                        center = bounds.center.y
                        size = bounds.size.y
                    }

                    else -> {
                        o = origin.z
                        d = delta.z
                        center = bounds.center.z
                        size = bounds.size.z
                    }
                }
                val low = center - size / 2
                val high = center + size / 2
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
