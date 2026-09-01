package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LookBehavior
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.ScenePlayerPolicy
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import kotlin.math.atan2
import kotlin.math.sqrt
import net.minestom.server.entity.Player

internal class LookController(
    private val clock: SceneClock,
    private val playerPolicy: ScenePlayerPolicy,
) {
    private val updatedAt = mutableMapOf<gg.grounds.scene.format.LocalId, Long>()
    private val rotations = mutableMapOf<gg.grounds.scene.format.LocalId, EulerRotation>()

    fun update(players: List<Player>, activeNpcs: List<ActiveElement>) {
        val now = clock.nanoTime()
        activeNpcs.forEach { active ->
            val npc = active.npc ?: return@forEach
            val behavior = npc.look as? LookBehavior.TrackNearest ?: return@forEach
            val entity = active.npcEntities?.interaction ?: return@forEach
            val target =
                players
                    .filter { player ->
                        if (!playerPolicy.isEligible(player)) return@filter false
                        val dx = player.position.x() - npc.transform.position.x
                        val dy = player.position.y() + player.eyeHeight - npc.transform.position.y
                        val dz = player.position.z() - npc.transform.position.z
                        dx * dx + dy * dy + dz * dz <= behavior.maxDistance * behavior.maxDistance
                    }
                    .minWithOrNull(
                        compareBy<Player>(
                            {
                                distanceSquared(
                                    it,
                                    npc.transform.position.x,
                                    npc.transform.position.y,
                                    npc.transform.position.z,
                                )
                            },
                            { it.uuid.toString() },
                        )
                    ) ?: return@forEach
            val current = rotations[active.elementId] ?: npc.transform.rotation
            val dx = target.position.x() - npc.transform.position.x
            val dy = target.position.y() + target.eyeHeight - npc.transform.position.y
            val dz = target.position.z() - npc.transform.position.z
            val desiredYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            val desiredPitch =
                if (behavior.yawOnly) current.pitch.toFloat()
                else Math.toDegrees(-atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
            val elapsed =
                updatedAt[active.elementId]?.let { (now - it).coerceAtLeast(0L) / 1_000_000_000.0 }
                    ?: 0.0
            val delta = (behavior.maxTurnDegreesPerSecond * elapsed).toFloat()
            val rotation =
                EulerRotation(
                    clampAngle(current.yaw.toFloat(), desiredYaw, delta).toDouble(),
                    clampAngle(current.pitch.toFloat(), desiredPitch, delta).toDouble(),
                    current.roll,
                )
            rotations[active.elementId] = rotation
            updatedAt[active.elementId] = now
            active.handles.forEach {
                it.applyTransform(
                    SceneRenderTransform(npc.transform.copy(rotation = rotation), null)
                )
            }
            entity.setView(rotation.yaw.toFloat(), rotation.pitch.toFloat())
        }
    }

    private fun distanceSquared(player: Player, x: Double, y: Double, z: Double): Double {
        val dx = player.position.x() - x
        val dy = player.position.y() + player.eyeHeight - y
        val dz = player.position.z() - z
        return dx * dx + dy * dy + dz * dz
    }

    private fun clampAngle(current: Float, desired: Float, maximumDelta: Float): Float {
        val delta = ((desired - current + 540f) % 360f) - 180f
        return current + delta.coerceIn(-maximumDelta, maximumDelta)
    }
}
