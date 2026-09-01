package gg.grounds.scene.minestom.internal.trigger

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneAction
import gg.grounds.scene.format.SceneHand
import gg.grounds.scene.format.SceneTrigger
import java.util.UUID

internal data class SceneTriggerInput(
    val playerId: UUID,
    val npcId: LocalId,
    val trigger: SceneTrigger,
    val hand: SceneHand?,
    val acceptedNanos: Long,
)

internal data class BindingKey(val playerId: UUID, val npcId: LocalId, val bindingIndex: Int)

internal data class PendingActionChain(
    val key: BindingKey,
    val generation: Long,
    val input: SceneTriggerInput,
    val actions: List<SceneAction>,
)
