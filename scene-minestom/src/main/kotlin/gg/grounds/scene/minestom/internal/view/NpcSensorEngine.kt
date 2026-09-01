package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.ScenePlayerPolicy
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import java.util.TreeMap
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
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
    private val cellEdge: Double = 32.0,
) {
    private val hovered = mutableMapOf<UUID, LocalId>()
    private val nearby = mutableSetOf<ViewerElementKey>()
    private val active = mutableMapOf<LocalId, ActiveElement>()
    private val cells = mutableMapOf<SensorCellKey, MutableMap<LocalId, ActiveElement>>()
    private var maximumRadius = interactionReach
    private var lastVisited = 0

    fun activate(element: ActiveElement) {
        val npc = element.npc ?: return
        deactivate(element.elementId)
        active[element.elementId] = element
        cells
            .getOrPut(cellFor(npc.transform.position.x, npc.transform.position.z)) {
                TreeMap(compareBy(LocalId::value))
            }[element.elementId] = element
        maximumRadius = max(maximumRadius, npc.proximity?.exitRadius ?: 0.0)
    }

    fun deactivate(elementId: LocalId) {
        val removed = active.remove(elementId) ?: return
        val npc = removed.npc ?: return
        cells[cellFor(npc.transform.position.x, npc.transform.position.z)]?.let { cell ->
            cell.remove(elementId)
            if (cell.isEmpty())
                cells.remove(cellFor(npc.transform.position.x, npc.transform.position.z))
        }
        hovered.entries.removeIf { it.value == elementId }
        maximumRadius =
            active.values
                .maxOfOrNull { it.npc?.proximity?.exitRadius ?: interactionReach }
                ?.coerceAtLeast(interactionReach) ?: interactionReach
    }

    fun update(players: List<Player>): List<SensorTransition> {
        val transitions = mutableListOf<SensorTransition>()
        val visited = linkedSetOf<LocalId>()
        players
            .sortedBy { it.uuid.toString() }
            .forEach { player ->
                if (!playerPolicy.isEligible(player)) {
                    transitions += removePlayer(player.uuid)
                    return@forEach
                }
                val npcs = candidates(player, visited)
                val eye = player.eyePosition()
                val direction = player.position.direction().let { Vec3(it.x(), it.y(), it.z()) }
                val newHover =
                    npcs
                        .mapNotNull { (active, npc) ->
                            if (!npc.visible) return@mapNotNull null
                            raycaster.rayDistance(active, eye, direction, interactionReach)?.let {
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
                    val position =
                        active
                            .transformOr(
                                gg.grounds.scene.minestom.SceneRenderTransform(npc.transform, null)
                            )
                            .root
                            .position
                    val dx = player.position.x() - position.x
                    val dz = player.position.z() - position.z
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
        lastVisited = visited.size
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

    fun clear() {
        hovered.clear()
        nearby.clear()
        active.clear()
        cells.clear()
        maximumRadius = interactionReach
        lastVisited = 0
    }

    fun visitedNpcCount(): Int = lastVisited

    private fun candidates(
        player: Player,
        visited: MutableSet<LocalId>,
    ): List<Pair<ActiveElement, gg.grounds.scene.format.Npc>> {
        val minimum =
            cellFor(player.position.x() - maximumRadius, player.position.z() - maximumRadius)
        val maximum =
            cellFor(player.position.x() + maximumRadius, player.position.z() + maximumRadius)
        return buildList {
            for (x in minimum.x..maximum.x) for (z in minimum.z..maximum.z) {
                cells[SensorCellKey(x, z)].orEmpty().values.forEach { element ->
                    visited += element.elementId
                    element.npc?.let { add(element to it) }
                }
            }
        }
    }

    private fun cellFor(x: Double, z: Double) =
        SensorCellKey(floor(x / cellEdge).toInt(), floor(z / cellEdge).toInt())

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

private data class SensorCellKey(val x: Int, val z: Int)
