package gg.grounds.scene.minestom.internal.trigger

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.ScenePlayerPolicy
import gg.grounds.scene.minestom.internal.NANOS_PER_MILLI
import gg.grounds.scene.minestom.internal.millisToNanosSaturated
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection

class SceneTriggerPipelineTest {
    @Test
    fun `conditions require every hand sneaking permission and game mode match`() {
        val player =
            player(instance()).also {
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
        val instance = instance()
        val player = player(instance)
        val first = binding(debounce = 10, cooldown = 20)
        val second = binding()
        val engine =
            TriggerEngine(
                instance,
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

    @Test
    fun `accept admits only a live eligible player in the target instance`() {
        val target = instance()
        val player = player(target)
        val binding = binding()
        val resolver: (UUID) -> Player? = { id -> if (id == player.uuid) player else null }

        assertEquals(
            emptyList(),
            TriggerEngine(target, { null }, Policy(), { listOf(binding) })
                .accept(input(player.uuid, 1)),
        )
        assertEquals(
            listOf(0),
            TriggerEngine(target, resolver, Policy(), { listOf(binding) })
                .accept(input(player.uuid, 1))
                .map { it.key.bindingIndex },
        )
        assertEquals(
            emptyList(),
            TriggerEngine(instance(), resolver, Policy(), { listOf(binding) })
                .accept(input(player.uuid, 1)),
        )
        assertEquals(
            emptyList(),
            TriggerEngine(target, resolver, Policy(eligible = false), { listOf(binding) })
                .accept(input(player.uuid, 1)),
        )
    }

    @Test
    fun `debounce and cooldown saturate millis and survive monotonic nano wraparound`() {
        val largestExactlyRepresentableMillis = Long.MAX_VALUE / NANOS_PER_MILLI
        assertEquals(
            largestExactlyRepresentableMillis * NANOS_PER_MILLI,
            millisToNanosSaturated(largestExactlyRepresentableMillis),
        )
        assertEquals(Long.MAX_VALUE, millisToNanosSaturated(Long.MAX_VALUE))
        val instance = instance()
        val player = player(instance)
        val start = Long.MAX_VALUE - 5L
        val afterWrap = Long.MIN_VALUE + 5L
        val debounce =
            TriggerEngine(
                instance,
                { player },
                Policy(),
                { listOf(binding(debounce = Long.MAX_VALUE)) },
            )
        val first = debounce.accept(input(player.uuid, start)).single()
        debounce.complete(first.key, first.generation, false, start)
        assertEquals(emptyList(), debounce.accept(input(player.uuid, afterWrap)))

        val cooldown =
            TriggerEngine(
                instance,
                { player },
                Policy(),
                { listOf(binding(cooldown = Long.MAX_VALUE)) },
            )
        val succeeded = cooldown.accept(input(player.uuid, start)).single()
        cooldown.complete(succeeded.key, succeeded.generation, true, start)
        assertEquals(emptyList(), cooldown.accept(input(player.uuid, afterWrap)))
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

    private fun instance(): Instance {
        MinecraftServer.init()
        return MinecraftServer.getInstanceManager().createInstanceContainer()
    }

    private fun player(instance: Instance): Player =
        Player(Connection(), GameProfile(UUID.randomUUID(), "test")).also {
            it.setInstance(instance, Pos.ZERO).join()
        }

    private class Policy(
        private val permissions: Set<String> = emptySet(),
        private val eligible: Boolean = true,
    ) : ScenePlayerPolicy {
        override fun isEligible(player: Player) = eligible

        override fun hasPermission(player: Player, permission: String) = permission in permissions
    }

    private class Connection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }
}
