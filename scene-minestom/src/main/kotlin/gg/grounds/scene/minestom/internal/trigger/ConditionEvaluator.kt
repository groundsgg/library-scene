package gg.grounds.scene.minestom.internal.trigger

import gg.grounds.scene.format.GameModeCondition
import gg.grounds.scene.format.HandCondition
import gg.grounds.scene.format.PermissionCondition
import gg.grounds.scene.format.SceneCondition
import gg.grounds.scene.format.SceneGameMode
import gg.grounds.scene.format.SceneHand
import gg.grounds.scene.format.SneakingCondition
import gg.grounds.scene.minestom.ScenePlayerPolicy
import net.minestom.server.entity.Player

internal class ConditionEvaluator(private val playerPolicy: ScenePlayerPolicy) {
    fun matches(player: Player, hand: SceneHand?, conditions: List<SceneCondition>): Boolean =
        conditions.all { condition ->
            when (condition) {
                is HandCondition -> hand == condition.hand
                is SneakingCondition -> player.isSneaking == condition.sneaking
                is PermissionCondition -> playerPolicy.hasPermission(player, condition.permission)
                is GameModeCondition -> player.gameMode.sceneGameMode() == condition.gameMode
            }
        }

    private fun net.minestom.server.entity.GameMode.sceneGameMode() =
        when (this) {
            net.minestom.server.entity.GameMode.SURVIVAL -> SceneGameMode.SURVIVAL
            net.minestom.server.entity.GameMode.CREATIVE -> SceneGameMode.CREATIVE
            net.minestom.server.entity.GameMode.ADVENTURE -> SceneGameMode.ADVENTURE
            net.minestom.server.entity.GameMode.SPECTATOR -> SceneGameMode.SPECTATOR
        }
}
