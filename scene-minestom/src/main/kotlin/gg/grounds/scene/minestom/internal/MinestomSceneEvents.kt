package gg.grounds.scene.minestom.internal

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneHand
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import gg.grounds.scene.minestom.internal.trigger.SceneTriggerInput
import gg.grounds.scene.minestom.internal.view.BoundsRaycaster
import java.util.UUID
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventFilter
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.trait.InstanceEvent

internal class MinestomSceneEvents(
    name: String,
    private val interactionElementId: (UUID) -> LocalId?,
    private val activeElement: (LocalId) -> ActiveElement?,
    private val acceptedNanos: () -> Long,
    private val accept: (SceneTriggerInput) -> Unit,
    private val disconnect: (Player) -> Unit,
) {
    val node: EventNode<InstanceEvent> = EventNode.type(name, EventFilter.INSTANCE)

    private val raycaster = BoundsRaycaster()

    init {
        node.addListener(PlayerEntityInteractEvent::class.java, ::rightClick)
        node.addListener(EntityAttackEvent::class.java, ::leftClick)
        node.addListener(PlayerDisconnectEvent::class.java) { disconnect(it.player) }
    }

    private fun rightClick(event: PlayerEntityInteractEvent) {
        acceptClick(
            event.player,
            event.target.uuid,
            SceneTrigger.RIGHT_CLICK,
            when (event.hand) {
                PlayerHand.MAIN -> SceneHand.MAIN
                PlayerHand.OFF -> SceneHand.OFF
            },
        )
    }

    private fun leftClick(event: EntityAttackEvent) {
        val player = event.entity as? Player ?: return
        acceptClick(player, event.target.uuid, SceneTrigger.LEFT_CLICK, SceneHand.MAIN)
    }

    private fun acceptClick(
        player: Player,
        interactionId: UUID,
        trigger: SceneTrigger,
        hand: SceneHand,
    ) {
        val elementId = interactionElementId(interactionId) ?: return
        val active = activeElement(elementId) ?: return
        val npc = active.npc?.takeIf { it.visible } ?: return
        val eye =
            Vec3(player.position.x(), player.position.y() + player.eyeHeight, player.position.z())
        val direction = player.position.direction().let { Vec3(it.x(), it.y(), it.z()) }
        if (raycaster.rayDistance(active, eye, direction, INTERACTION_REACH) == null) return
        accept(SceneTriggerInput(player.uuid, elementId, trigger, hand, acceptedNanos()))
    }

    private companion object {
        const val INTERACTION_REACH = 5.0
    }
}
