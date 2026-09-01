package gg.grounds.scene.minestom

import net.minestom.server.entity.Player

interface ScenePlayerPolicy {
    fun isEligible(player: Player): Boolean
    fun hasPermission(player: Player, permission: String): Boolean
}
