package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.ScenePlayerPolicy
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import java.util.UUID
import kotlin.math.abs
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

internal data class SensorTransition(
    val playerId: UUID,
    val elementId: LocalId,
    val trigger: SceneTrigger,
)

internal class NpcSensorEngine(
    private val instance: Instance,
    private val playerPolicy: ScenePlayerPolicy,
    private val interactionReach: Double = 5.0,
    private val raycaster: BoundsRaycaster = BoundsRaycaster(),
    private val queries: MinestomSensorQueries = MinestomSensorQueries(instance, interactionReach),
) {
    private val hovered = mutableMapOf<UUID, LocalId>()
    private val proximityMembers = mutableMapOf<LocalId, MutableSet<UUID>>()
    private val proximityElements = mutableMapOf<UUID, MutableSet<LocalId>>()
    private val active = mutableMapOf<LocalId, ActiveElement>()
    private val interactions = mutableMapOf<UUID, ActiveElement>()
    private val oversizedHover = mutableMapOf<LocalId, ActiveElement>()
    private var lastVisited = 0
    private var lastProximityMembershipWork = 0

    fun activate(element: ActiveElement) {
        val npc = element.npc ?: return
        deactivate(element.elementId)
        active[element.elementId] = element
        if (npc.visible) element.npcEntities?.interaction?.let { interactions[it.uuid] = element }
        refresh(element)
    }

    fun deactivate(elementId: LocalId) {
        val removed = active.remove(elementId) ?: return
        removed.npcEntities?.interaction?.uuid?.let(interactions::remove)
        oversizedHover.remove(elementId)
        hovered.entries.removeIf { it.value == elementId }
    }

    fun refresh(element: ActiveElement) {
        val npc = element.npc ?: return
        if (!npc.visible || element.npcEntities?.interaction == null) return
        val position = element.npcEntities.interaction.position
        val bounds = element.currentBounds()
        val extent =
            maxOf(
                abs(bounds.min.x - position.x()),
                abs(bounds.max.x - position.x()),
                abs(bounds.min.z - position.z()),
                abs(bounds.max.z - position.z()),
            )
        if (extent > 16.0) oversizedHover[element.elementId] = element
        else oversizedHover.remove(element.elementId)
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
                val npcs = hoverCandidates(player, visited)
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
            }
        lastProximityMembershipWork = 0
        transitions += reconcileProximity(players)
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
        proximityElements
            .remove(playerId)
            .orEmpty()
            .sortedBy { it.value }
            .forEach { elementId ->
                val members = proximityMembers[elementId] ?: return@forEach
                if (!members.remove(playerId)) return@forEach
                if (members.isEmpty()) proximityMembers.remove(elementId)
                transitions += SensorTransition(playerId, elementId, SceneTrigger.PROXIMITY_LEAVE)
            }
        return transitions.sortedWith(
            compareBy({ it.elementId.value }, { triggerOrder(it.trigger) })
        )
    }

    fun clear() {
        hovered.clear()
        proximityMembers.clear()
        proximityElements.clear()
        active.clear()
        interactions.clear()
        oversizedHover.clear()
        lastVisited = 0
        lastProximityMembershipWork = 0
    }

    fun visitedNpcCount(): Int = lastVisited

    fun proximityMembershipWorkCount(): Int = lastProximityMembershipWork

    private fun hoverCandidates(
        player: Player,
        visited: MutableSet<LocalId>,
    ): List<Pair<ActiveElement, gg.grounds.scene.format.Npc>> {
        val candidates = linkedMapOf<LocalId, ActiveElement>()
        queries.hoverInteractions(player.eyePosition().asPoint()).forEach { entity ->
            interactions[entity.uuid]?.let { candidates[it.elementId] = it }
        }
        oversizedHover.forEach { (id, element) -> candidates[id] = element }
        return candidates.values
            .sortedBy { it.elementId.value }
            .mapNotNull { element ->
                visited += element.elementId
                element.npc?.let { element to it }
            }
    }

    private fun reconcileProximity(players: List<Player>): List<SensorTransition> = buildList {
        val connected = players.associateBy(Player::getUuid)
        active.values
            .sortedBy { it.elementId.value }
            .forEach { element ->
                val npc = element.npc ?: return@forEach
                val sensor = npc.proximity ?: return@forEach
                val point = element.currentTransform.root.position.asPoint()
                val candidates =
                    (queries.proximityPlayers(point, sensor.exitRadius)
                            ?: players
                                .asSequence()
                                .filter { it.instance === instance }
                                .sortedBy(Player::getUuid)
                                .toList())
                        .associateBy(Player::getUuid)
                val retainedIds = proximityMembers[element.elementId].orEmpty()
                lastProximityMembershipWork += retainedIds.size
                val relevantIds = (retainedIds + candidates.keys).toSortedSet()
                relevantIds.forEach { playerId ->
                    val player = connected[playerId]
                    val eligible = player != null && playerPolicy.isEligible(player)
                    val candidate = candidates[playerId]
                    if (!eligible || candidate == null) {
                        if (removeProximityMember(playerId, element.elementId))
                            add(
                                SensorTransition(
                                    playerId,
                                    element.elementId,
                                    SceneTrigger.PROXIMITY_LEAVE,
                                )
                            )
                        return@forEach
                    }
                    val dx = candidate.position.x() - point.x
                    val dz = candidate.position.z() - point.z
                    val horizontalDistance = Math.hypot(dx, dz)
                    val isNearby = playerId in proximityMembers[element.elementId].orEmpty()
                    if (!isNearby && horizontalDistance <= sensor.enterRadius) {
                        addProximityMember(playerId, element.elementId)
                        add(
                            SensorTransition(
                                playerId,
                                element.elementId,
                                SceneTrigger.PROXIMITY_ENTER,
                            )
                        )
                    } else if (isNearby && horizontalDistance > sensor.exitRadius) {
                        removeProximityMember(playerId, element.elementId)
                        add(
                            SensorTransition(
                                playerId,
                                element.elementId,
                                SceneTrigger.PROXIMITY_LEAVE,
                            )
                        )
                    }
                }
            }
    }

    private fun addProximityMember(playerId: UUID, elementId: LocalId): Boolean {
        val added = proximityMembers.getOrPut(elementId, ::mutableSetOf).add(playerId)
        if (added) proximityElements.getOrPut(playerId, ::mutableSetOf).add(elementId)
        return added
    }

    private fun removeProximityMember(playerId: UUID, elementId: LocalId): Boolean {
        val members = proximityMembers[elementId] ?: return false
        if (!members.remove(playerId)) return false
        if (members.isEmpty()) proximityMembers.remove(elementId)
        proximityElements[playerId]?.let { elements ->
            elements.remove(elementId)
            if (elements.isEmpty()) proximityElements.remove(playerId)
        }
        return true
    }

    private fun Player.eyePosition() = Vec3(position.x(), position.y() + eyeHeight, position.z())

    private fun Vec3.asPoint() = Vec(x, y, z)

    private fun triggerOrder(trigger: SceneTrigger) =
        when (trigger) {
            SceneTrigger.HOVER_ENTER -> 0
            SceneTrigger.HOVER_LEAVE -> 1
            SceneTrigger.PROXIMITY_ENTER -> 2
            SceneTrigger.PROXIMITY_LEAVE -> 3
            else -> 4
        }
}
