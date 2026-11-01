package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LookBehavior
import gg.grounds.scene.minestom.HeadLookRenderedAssetHandle
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.ScenePlayerPolicy
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.elapsedNanos
import gg.grounds.scene.minestom.internal.geometry.affine
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sign
import kotlin.math.sqrt
import net.minestom.server.entity.Player

internal class LookController(
    private val clock: SceneClock,
    private val playerPolicy: ScenePlayerPolicy,
) {
    private val updatedAt = mutableMapOf<gg.grounds.scene.format.LocalId, Long>()
    private val headAngles = mutableMapOf<gg.grounds.scene.format.LocalId, Pair<Double, Double>>()

    fun update(
        players: List<Player>,
        activeNpcs: List<ActiveElement>,
        onFailure: (ActiveElement, Throwable) -> Unit = { _, _ -> },
    ) {
        val activeIds = activeNpcs.map { it.elementId }.toSet()
        updatedAt.keys.removeIf { it !in activeIds }
        headAngles.keys.removeIf { it !in activeIds }
        val now = clock.nanoTime()
        activeNpcs.forEach { active ->
            try {
                updateActive(players, active, now)
            } catch (error: Throwable) {
                onFailure(active, error)
            }
        }
    }

    fun removeElement(elementId: gg.grounds.scene.format.LocalId) {
        updatedAt.remove(elementId)
        headAngles.remove(elementId)
    }

    private fun updateActive(players: List<Player>, active: ActiveElement, now: Long) {
        val npc = active.npc ?: return
        val behavior = npc.look as? LookBehavior.TrackNearest ?: return
        if (active.npcEntities?.interaction == null) return
        val currentTransform = active.transformOr(SceneRenderTransform(npc.transform, null))
        val heads =
            active.handles.filterIsInstance<HeadLookRenderedAssetHandle>().filter {
                it.headLookOrigin != null
            }
        val aim =
            heads.firstOrNull()?.headLookOrigin?.let { currentTransform.affine().transform(it) }
                ?: currentTransform.root.position
        val target =
            players
                .filter { player ->
                    if (!playerPolicy.isEligible(player)) return@filter false
                    val dx = player.position.x() - aim.x
                    val dy = player.position.y() + player.eyeHeight - aim.y
                    val dz = player.position.z() - aim.z
                    dx * dx + dy * dy + dz * dz <= behavior.maxDistance * behavior.maxDistance
                }
                .minWithOrNull(
                    compareBy<Player>(
                        { distanceSquared(it, aim.x, aim.y, aim.z) },
                        { it.uuid.toString() },
                    )
                )
                ?: run {
                    updatedAt.remove(active.elementId)
                    return
                }
        val current = currentTransform.root.rotation
        val dx = target.position.x() - aim.x
        val dy = target.position.y() + target.eyeHeight - aim.y
        val dz = target.position.z() - aim.z
        // Scene's right-handed +yaw turns +Z toward +X; entity yaw has the opposite sign.
        val desiredYaw = Math.toDegrees(atan2(dx, dz)).toFloat()
        val desiredPitch =
            if (behavior.yawOnly) current.pitch.toFloat()
            else Math.toDegrees(-atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
        val elapsed =
            updatedAt[active.elementId]?.let { elapsedNanos(now, it) / 1_000_000_000.0 } ?: 0.0
        val delta = (behavior.maxTurnDegreesPerSecond * elapsed).toFloat()
        updatedAt[active.elementId] = now
        if (heads.isNotEmpty()) {
            val previous = headAngles[active.elementId] ?: (0.0 to 0.0)
            val relativeTarget = wrap(desiredYaw.toDouble() - current.yaw)
            val bodyTarget =
                if (abs(relativeTarget) > 60.0) desiredYaw.toDouble() - sign(relativeTarget) * 60.0
                else current.yaw
            val bodyYaw =
                wrap(clampAngle(current.yaw.toFloat(), bodyTarget.toFloat(), delta).toDouble())
            val headWorldYaw =
                clampAngle((current.yaw + previous.first).toFloat(), desiredYaw, delta).toDouble()
            val headYaw = wrap(headWorldYaw - bodyYaw).coerceIn(-75.0, 75.0)
            val headPitch =
                if (behavior.yawOnly) 0.0
                else
                    clampAngle(previous.second.toFloat(), desiredPitch.coerceIn(-60f, 60f), delta)
                        .toDouble()
            headAngles[active.elementId] = headYaw to headPitch
            if (bodyYaw != current.yaw || current.pitch != 0.0 || current.roll != 0.0) {
                active.updateRuntimeTransform(
                    SceneRenderTransform(
                        currentTransform.root.copy(rotation = EulerRotation(bodyYaw, 0.0, 0.0)),
                        null,
                    )
                )
            }
            heads.forEach { it.applyHeadLook(headYaw, headPitch) }
            return
        }
        val rotation =
            EulerRotation(
                clampAngle(current.yaw.toFloat(), desiredYaw, delta).toDouble(),
                clampAngle(current.pitch.toFloat(), desiredPitch, delta).toDouble(),
                current.roll,
            )
        active.updateRuntimeTransform(
            SceneRenderTransform(currentTransform.root.copy(rotation = rotation), null)
        )
    }

    private fun distanceSquared(player: Player, x: Double, y: Double, z: Double): Double {
        val dx = player.position.x() - x
        val dy = player.position.y() + player.eyeHeight - y
        val dz = player.position.z() - z
        return dx * dx + dy * dy + dz * dz
    }

    private fun clampAngle(current: Float, desired: Float, maximumDelta: Float): Float {
        val delta = wrap((desired - current).toDouble()).toFloat()
        return current + delta.coerceIn(-maximumDelta, maximumDelta)
    }

    private fun wrap(angle: Double) = ((angle + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}
