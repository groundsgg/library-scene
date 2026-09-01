package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.LookBehavior
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.ProximitySensor
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.RenderedAssetHandle
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.ScenePlayerPolicy
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.SceneViewerVisualState
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import gg.grounds.scene.minestom.internal.runtime.NpcPlatformEntities
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection

class ViewerStateTest {
    @Test
    fun `overlapping bounds choose ray distance then local id`() {
        val player = player(PLAYER_ONE, Pos(0.0, 0.0, 0.0))
        val farFirst = sensorActive(npc("a-far", z = 3.0))
        val nearLast = sensorActive(npc("z-near", z = 2.0))

        assertEquals(
            listOf(transition(PLAYER_ONE, "z-near", SceneTrigger.HOVER_ENTER)),
            sensor().update(listOf(player), listOf(farFirst, nearLast)),
        )

        val b = sensorActive(npc("b", z = 2.0))
        val a = sensorActive(npc("a", z = 2.0))
        assertEquals(
            listOf(transition(PLAYER_ONE, "a", SceneTrigger.HOVER_ENTER)),
            sensor().update(listOf(player), listOf(b, a)),
        )
    }

    @Test
    fun `hover emits enter once and leave when aim changes`() {
        val player = player(PLAYER_ONE, Pos(0.0, 0.0, 0.0))
        val active = sensorActive(npc("guide", z = 2.0))
        val sensor = sensor()

        assertEquals(
            listOf(transition(PLAYER_ONE, "guide", SceneTrigger.HOVER_ENTER)),
            sensor.update(listOf(player), listOf(active)),
        )
        assertEquals(emptyList(), sensor.update(listOf(player), listOf(active)))

        player.move(Pos(0.0, 0.0, 0.0, 180f, 0f))
        assertEquals(
            listOf(transition(PLAYER_ONE, "guide", SceneTrigger.HOVER_LEAVE)),
            sensor.update(listOf(player), listOf(active)),
        )
    }

    @Test
    fun `proximity hysteresis survives inactive npc without duplicate enter`() {
        val player = player(PLAYER_ONE, Pos(2.0, 0.0, 0.0))
        val active =
            sensorActive(npc("guide", proximity = ProximitySensor(3.0, 4.0), boundsY = 8.0))
        val sensor = sensor()

        assertEquals(
            listOf(transition(PLAYER_ONE, "guide", SceneTrigger.PROXIMITY_ENTER)),
            sensor.update(listOf(player), listOf(active)),
        )
        player.move(Pos(3.5, 0.0, 0.0))
        assertEquals(emptyList(), sensor.update(listOf(player), listOf(active)))
        assertEquals(emptyList(), sensor.update(listOf(player), emptyList()))

        player.move(Pos(2.0, 0.0, 0.0))
        assertEquals(emptyList(), sensor.update(listOf(player), listOf(active)))
        player.move(Pos(4.1, 0.0, 0.0))
        assertEquals(
            listOf(transition(PLAYER_ONE, "guide", SceneTrigger.PROXIMITY_LEAVE)),
            sensor.update(listOf(player), listOf(active)),
        )
    }

    @Test
    fun `disconnect cleanup removes only that player's sensor and visual state`() {
        val first = player(PLAYER_ONE, Pos(0.0, 0.0, 0.0))
        val active = sensorActive(npc("guide", z = 2.0, proximity = ProximitySensor(3.0, 4.0)))
        val sensor = sensor()
        sensor.update(listOf(first), listOf(active))

        assertEquals(
            listOf(
                transition(PLAYER_ONE, "guide", SceneTrigger.HOVER_LEAVE),
                transition(PLAYER_ONE, "guide", SceneTrigger.PROXIMITY_LEAVE),
            ),
            sensor.removePlayer(PLAYER_ONE),
        )
        assertEquals(emptyList(), sensor.removePlayer(PLAYER_ONE))

        val viewers = ViewerStateStore()
        val firstGuide = ViewerElementKey(PLAYER_ONE, LocalId("guide"))
        val firstOther = ViewerElementKey(PLAYER_ONE, LocalId("other"))
        val secondGuide = ViewerElementKey(PLAYER_TWO, LocalId("guide"))
        viewers.setScale(firstGuide, 1.5)
        viewers.setHighlight(firstOther, true)
        viewers.setHighlight(secondGuide, true)

        viewers.removePlayer(PLAYER_ONE)

        assertEquals(SceneViewerVisualState(), viewers.visualState(firstGuide))
        assertEquals(SceneViewerVisualState(), viewers.visualState(firstOther))
        assertEquals(SceneViewerVisualState(highlighted = true), viewers.visualState(secondGuide))
    }

    @Test
    fun `look uses uuid tie yaw-only pitch and clock bounded turn`() {
        val clock = ManualClock()
        val lowerUuid = player(PLAYER_ONE, Pos(5.0, 0.0, 0.0))
        val higherUuid = player(PLAYER_TWO, Pos(-5.0, 0.0, 0.0))
        val handle = RecordingHandle()
        val active =
            lookActive(
                npc(
                    "guide",
                    rotation = EulerRotation(0.0, 17.0, 0.0),
                    look = LookBehavior.TrackNearest(20.0, yawOnly = true, 45.0),
                ),
                handle,
            )
        val look = LookController(clock, AlwaysEligible)

        look.update(listOf(higherUuid, lowerUuid), listOf(active))
        clock.advanceSeconds(1)
        look.update(listOf(higherUuid, lowerUuid), listOf(active))

        assertEquals(EulerRotation(-45.0, 17.0, 0.0), handle.rotations.last())
    }

    @Test
    fun `look prunes absent state and reactivation starts from authored rotation`() {
        val clock = ManualClock()
        val player = player(PLAYER_ONE, Pos(5.0, 0.0, 0.0))
        val authored = EulerRotation(10.0, 5.0, 0.0)
        val npc =
            npc(
                "guide",
                rotation = authored,
                look = LookBehavior.TrackNearest(20.0, yawOnly = true, 30.0),
            )
        val firstHandle = RecordingHandle()
        val look = LookController(clock, AlwaysEligible)

        look.update(listOf(player), listOf(lookActive(npc, firstHandle)))
        clock.advanceSeconds(1)
        look.update(listOf(player), listOf(lookActive(npc, firstHandle)))
        assertEquals(EulerRotation(-20.0, 5.0, 0.0), firstHandle.rotations.last())

        clock.advanceSeconds(1)
        look.update(listOf(player), emptyList())
        clock.advanceSeconds(1)
        val reactivatedHandle = RecordingHandle()
        look.update(listOf(player), listOf(lookActive(npc, reactivatedHandle)))

        assertEquals(authored, reactivatedHandle.rotations.single())
    }

    private fun sensor() = NpcSensorEngine(AlwaysEligible)

    private fun sensorActive(npc: Npc) = ActiveElement(npc.id, 1L, emptyList(), null, npc)

    private fun lookActive(npc: Npc, handle: RecordingHandle) =
        ActiveElement(
            npc.id,
            1L,
            listOf(handle),
            NpcPlatformEntities(null, Entity(EntityType.INTERACTION)),
            npc,
        )

    private fun npc(
        id: String,
        z: Double = 0.0,
        boundsY: Double = 1.62,
        rotation: EulerRotation = EulerRotation(0.0, 0.0, 0.0),
        look: LookBehavior = LookBehavior.Fixed,
        proximity: ProximitySensor? = null,
    ) =
        Npc(
            LocalId(id),
            null,
            Transform(Vec3(0.0, 0.0, z), rotation, Vec3(1.0, 1.0, 1.0)),
            body = AssetKey("test:npc"),
            label = null,
            labelOffset = Vec3(0.0, 0.0, 0.0),
            look = look,
            initialAnimation = null,
            interactionBounds = LocalBounds(Vec3(0.0, boundsY, 0.0), Vec3(1.0, 1.0, 1.0)),
            proximity = proximity,
            bindings = emptyList(),
        )

    private fun player(id: UUID, position: Pos): TestPlayer {
        MinecraftServer.init()
        return TestPlayer(id).also { it.move(position) }
    }

    private fun transition(playerId: UUID, elementId: String, trigger: SceneTrigger) =
        SensorTransition(playerId, LocalId(elementId), trigger)

    private object AlwaysEligible : ScenePlayerPolicy {
        override fun isEligible(player: Player) = true

        override fun hasPermission(player: Player, permission: String) = true
    }

    private class ManualClock(private var now: Long = 0L) : SceneClock {
        override fun nanoTime() = now

        fun advanceSeconds(seconds: Long) {
            now += seconds * 1_000_000_000L
        }
    }

    private class TestPlayer(id: UUID) : Player(TestConnection(), GameProfile(id, "test-player")) {
        fun move(position: Pos) {
            setPositionInternal(position, position.yaw())
        }
    }

    private class TestConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    private class RecordingHandle : RenderedAssetHandle {
        val rotations = mutableListOf<EulerRotation>()

        override fun applyTransform(transform: SceneRenderTransform) {
            rotations += transform.root.rotation
        }

        override fun applyViewerState(player: Player, state: SceneViewerVisualState) = Unit

        override fun clearViewerState(player: Player) = Unit

        override fun startAnimation(animation: LocalId, elapsedMillis: Long) = Unit

        override fun stopAnimation(animation: LocalId?) = Unit

        override fun advanceAnimation(elapsedMillis: Long) = Unit

        override fun close() = Unit
    }

    private companion object {
        val PLAYER_ONE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val PLAYER_TWO: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    }
}
