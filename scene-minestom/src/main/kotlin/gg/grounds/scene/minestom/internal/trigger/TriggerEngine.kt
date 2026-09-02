package gg.grounds.scene.minestom.internal.trigger

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.TriggerBinding
import gg.grounds.scene.minestom.ScenePlayerPolicy
import gg.grounds.scene.minestom.internal.millisToNanosSaturated
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

internal class TriggerEngine(
    private val instance: Instance,
    private val playerResolver: (java.util.UUID) -> Player?,
    playerPolicy: ScenePlayerPolicy,
    private val bindingsFor: (LocalId) -> List<TriggerBinding>,
    private val stateStore: BindingStateStore = BindingStateStore(),
) {
    private val conditionEvaluator = ConditionEvaluator(playerPolicy)

    fun accept(input: SceneTriggerInput): List<PendingActionChain> {
        val player = playerResolver(input.playerId) ?: return emptyList()
        if (player.instance !== instance) return emptyList()
        if (!conditionEvaluatorPolicyEligible(player)) return emptyList()
        val normalizedInput =
            if (input.trigger == gg.grounds.scene.format.SceneTrigger.LEFT_CLICK) {
                input.copy(hand = gg.grounds.scene.format.SceneHand.MAIN)
            } else {
                input
            }
        return bindingsFor(input.npcId).mapIndexedNotNull { bindingIndex, binding ->
            if (
                binding.trigger != normalizedInput.trigger ||
                    !conditionEvaluator.matches(player, normalizedInput.hand, binding.conditions)
            ) {
                return@mapIndexedNotNull null
            }
            val key = BindingKey(input.playerId, input.npcId, bindingIndex)
            if (
                stateStore.isCoolingDown(
                    key,
                    normalizedInput.acceptedNanos,
                    millisToNanosSaturated(binding.cooldownMillis),
                )
            ) {
                return@mapIndexedNotNull null
            }
            stateStore
                .tryAccept(
                    key,
                    normalizedInput.acceptedNanos,
                    millisToNanosSaturated(binding.debounceMillis),
                )
                ?.let { PendingActionChain(key, it, normalizedInput, binding.actions) }
        }
    }

    fun complete(key: BindingKey, generation: Long, succeeded: Boolean, completedNanos: Long) =
        stateStore.complete(key, generation, succeeded, completedNanos)

    fun invalidatePlayer(playerId: java.util.UUID) = stateStore.invalidatePlayer(playerId)

    fun invalidateElement(elementId: LocalId) = stateStore.invalidateElement(elementId)

    fun clear() = stateStore.clear()

    private fun conditionEvaluatorPolicyEligible(player: Player): Boolean =
        policy.isEligible(player)

    private val policy: ScenePlayerPolicy = playerPolicy
}
