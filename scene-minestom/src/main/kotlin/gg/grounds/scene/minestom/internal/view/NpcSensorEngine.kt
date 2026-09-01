package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.ScenePlayerPolicy
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import java.util.UUID
import net.minestom.server.entity.Player

internal data class SensorTransition(
    val playerId: UUID,
    val elementId: LocalId,
    val trigger: SceneTrigger,
)

internal class NpcSensorEngine(
    private val playerPolicy: ScenePlayerPolicy,
    private val interactionReach: Double = 5.0,
    private val raycaster: BoundsRaycaster = BoundsRaycaster(),
) {
    private val hovered = mutableMapOf<UUID, LocalId>()
    private val nearby = mutableSetOf<ViewerElementKey>()

    fun update(players: List<Player>, activeNpcs: List<ActiveElement>): List<SensorTransition> {
        val npcs = activeNpcs.mapNotNull { active -> active.npc?.let { active to it } }
        val transitions = mutableListOf<SensorTransition>()
        players
            .sortedBy { it.uuid.toString() }
            .forEach { player ->
                if (!playerPolicy.isEligible(player)) {
                    transitions += removePlayer(player.uuid)
                    return@forEach
                }
                val eye = player.eyePosition()
                val direction = player.position.direction().let { Vec3(it.x(), it.y(), it.z()) }
                val newHover =
                    npcs
                        .mapNotNull { (active, npc) ->
                            raycaster.rayDistance(npc, eye, direction, interactionReach)?.let {
                                active.elementId to it
                            }
                        }
                        .minWithOrNull(
                            compareBy<Pair<LocalId, Double>>({ it.second }, { it.first.value })
                        )
                        ?.first
                val previousHover = hovered[player.uuid]
                if (previousHover != newHover) {
                    previousHover?.let {
                        transitions += SensorTransition(player.uuid, it, SceneTrigger.HOVER_LEAVE)
                    }
                    newHover?.let {
                        transitions += SensorTransition(player.uuid, it, SceneTrigger.HOVER_ENTER)
                    }
                    if (newHover == null) hovered.remove(player.uuid)
                    else hovered[player.uuid] = newHover
                }
                npcs.forEach { (active, npc) ->
                    val sensor = npc.proximity ?: return@forEach
                    val key = ViewerElementKey(player.uuid, active.elementId)
                    val dx = player.position.x() - npc.transform.position.x
                    val dz = player.position.z() - npc.transform.position.z
                    val distanceSquared = dx * dx + dz * dz
                    if (
                        key !in nearby && distanceSquared <= sensor.enterRadius * sensor.enterRadius
                    ) {
                        nearby += key
                        transitions +=
                            SensorTransition(
                                player.uuid,
                                active.elementId,
                                SceneTrigger.PROXIMITY_ENTER,
                            )
                    } else if (
                        key in nearby && distanceSquared > sensor.exitRadius * sensor.exitRadius
                    ) {
                        nearby -= key
                        transitions +=
                            SensorTransition(
                                player.uuid,
                                active.elementId,
                                SceneTrigger.PROXIMITY_LEAVE,
                            )
                    }
                }
            }
        return transitions.sortedWith(
            compareBy<SensorTransition>(
                { it.playerId.toString() },
                { it.elementId.value },
                { triggerOrder(it.trigger) },
            )
        )
    }

    fun removePlayer(playerId: UUID): List<SensorTransition> {
        val transitions = mutableListOf<SensorTransition>()
        hovered.remove(playerId)?.let {
            transitions += SensorTransition(playerId, it, SceneTrigger.HOVER_LEAVE)
        }
        nearby
            .filter { it.playerId == playerId }
            .forEach { key ->
                transitions +=
                    SensorTransition(playerId, key.elementId, SceneTrigger.PROXIMITY_LEAVE)
                nearby -= key
            }
        return transitions.sortedWith(
            compareBy({ it.elementId.value }, { triggerOrder(it.trigger) })
        )
    }

    private fun Player.eyePosition() = Vec3(position.x(), position.y() + eyeHeight, position.z())

    private fun triggerOrder(trigger: SceneTrigger) =
        when (trigger) {
            SceneTrigger.HOVER_ENTER -> 0
            SceneTrigger.HOVER_LEAVE -> 1
            SceneTrigger.PROXIMITY_ENTER -> 2
            SceneTrigger.PROXIMITY_LEAVE -> 3
            else -> 4
        }
}
