package gg.grounds.scene.minestom.internal.trigger

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.ScenePlayerPolicy
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection

class SceneTriggerPipelineTest {
    @Test
    fun `conditions require every hand sneaking permission and game mode match`() {
        val player =
            player().also {
                it.setGameMode(GameMode.ADVENTURE)
                it.setSneaking(true)
            }
        val evaluator = ConditionEvaluator(Policy(setOf("scene.use")))
        val conditions =
            listOf(
                HandCondition(SceneHand.MAIN),
                SneakingCondition(true),
                PermissionCondition("scene.use"),
                GameModeCondition(SceneGameMode.ADVENTURE),
            )

        assertEquals(true, evaluator.matches(player, SceneHand.MAIN, conditions))
        assertEquals(false, evaluator.matches(player, SceneHand.OFF, conditions))
        assertEquals(false, evaluator.matches(player, null, conditions))
        player.setSneaking(false)
        assertEquals(false, evaluator.matches(player, SceneHand.MAIN, conditions))
    }

    @Test
    fun `accepts matching bindings in document order and guards debounce inflight cooldown and invalidation`() {
        val player = player()
        val first = binding(debounce = 10, cooldown = 20)
        val second = binding()
        val engine =
            TriggerEngine(
                { id -> if (id == player.uuid) player else null },
                Policy(),
                { listOf(first, second) },
            )
        val input = input(player.uuid, 100_000_000)

        val accepted = engine.accept(input)
        assertEquals(listOf(0, 1), accepted.map { it.key.bindingIndex })
        assertEquals(emptyList(), engine.accept(input.copy(acceptedNanos = 105_000_000)))
        engine.complete(
            accepted[0].key,
            accepted[0].generation,
            succeeded = true,
            completedNanos = 110_000_000,
        )
        engine.complete(
            accepted[1].key,
            accepted[1].generation,
            succeeded = false,
            completedNanos = 110_000_000,
        )
        val retry = engine.accept(input.copy(acceptedNanos = 120_000_000))
        assertEquals(listOf(1), retry.map { it.key.bindingIndex })
        engine.invalidatePlayer(player.uuid)
        engine.complete(
            retry.single().key,
            retry.single().generation,
            succeeded = true,
            completedNanos = 122_000_000,
        )
        assertEquals(
            listOf(0, 1),
            engine.accept(input.copy(acceptedNanos = 132_000_000)).map { it.key.bindingIndex },
        )
        engine.invalidateElement(LocalId("guide"))
        assertEquals(
            listOf(1),
            engine.accept(input.copy(acceptedNanos = 133_000_000)).map { it.key.bindingIndex },
        )
    }

    private fun binding(debounce: Long = 0, cooldown: Long = 0) =
        TriggerBinding(
            SceneTrigger.LEFT_CLICK,
            emptyList(),
            cooldown,
            debounce,
            listOf(SendMessageAction(net.kyori.adventure.text.Component.text("ok"))),
        )

    private fun input(playerId: UUID, acceptedNanos: Long) =
        SceneTriggerInput(
            playerId,
            LocalId("guide"),
            SceneTrigger.LEFT_CLICK,
            SceneHand.MAIN,
            acceptedNanos,
        )

    private fun player(): Player {
        MinecraftServer.init()
        return Player(Connection(), GameProfile(UUID.randomUUID(), "test"))
    }

    private class Policy(private val permissions: Set<String> = emptySet()) : ScenePlayerPolicy {
        override fun isEligible(player: Player) = true

        override fun hasPermission(player: Player, permission: String) = permission in permissions
    }

    private class Connection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }
}
