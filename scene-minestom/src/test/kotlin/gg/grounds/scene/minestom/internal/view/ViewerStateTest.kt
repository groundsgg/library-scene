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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.other.InteractionMeta
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection

class ViewerStateTest {
    @Test
    fun `hover uses nearby Minestom Interaction chunks and exact ray ordering`() {
        val instance = instance()
        val sensor = NpcSensorEngine(instance, AlwaysEligible)
        val player = player(PLAYER_ONE, Pos.ZERO)
        val far = sensorActive(instance, npc("far", z = 3.0))
        val near = sensorActive(instance, npc("near", z = 2.0))
        sensor.activate(far)
        sensor.activate(near)

        assertEquals(
            listOf(transition(PLAYER_ONE, "near", SceneTrigger.HOVER_ENTER)),
            sensor.update(listOf(player)),
        )
    }

    @Test
    fun `large off-center current bounds remain hoverable through overflow candidates`() {
        val instance = instance()
        val sensor = NpcSensorEngine(instance, AlwaysEligible)
        val player = player(PLAYER_ONE, Pos(32.0, 0.0, -3.0))
        val npc = npc("wide", bounds = LocalBounds(Vec3(32.0, 1.62, 0.0), Vec3(2.0, 1.0, 1.0)))
        sensor.activate(sensorActive(instance, npc))

        assertEquals(
            listOf(transition(PLAYER_ONE, "wide", SceneTrigger.HOVER_ENTER)),
            sensor.update(listOf(player)),
        )
    }

    @Test
    fun `proximity queries Minestom players per npc and retains membership while inactive`() {
        val instance = instance()
        val sensor = NpcSensorEngine(instance, AlwaysEligible)
        val player =
            player(PLAYER_ONE, Pos(2.0, 0.0, 0.0)).also {
                it.setInstance(instance, it.position).join()
            }
        val active = sensorActive(instance, npc("guide", proximity = ProximitySensor(3.0, 4.0)))
        sensor.activate(active)

        assertEquals(
            listOf(transition(PLAYER_ONE, "guide", SceneTrigger.PROXIMITY_ENTER)),
            sensor.update(listOf(player)),
        )
        sensor.deactivate(active.elementId)
        assertEquals(emptyList(), sensor.update(listOf(player)))
    }

    @Test
    fun `proximity preserves horizontal radius across large vertical separation`() {
        val instance = instance()
        val sensor = NpcSensorEngine(instance, AlwaysEligible)
        val player =
            player(PLAYER_ONE, Pos(2.0, 100.0, 0.0)).also {
                it.setInstance(instance, it.position).join()
            }
        sensor.activate(sensorActive(instance, npc("guide", proximity = ProximitySensor(3.0, 4.0))))

        assertEquals(
            listOf(transition(PLAYER_ONE, "guide", SceneTrigger.PROXIMITY_ENTER)),
            sensor.update(listOf(player)),
        )
    }

    @Test
    fun `sensor candidate work excludes distant Interaction chunks`() {
        val instance = instance()
        val sensor = NpcSensorEngine(instance, AlwaysEligible)
        val player = player(PLAYER_ONE, Pos.ZERO)
        repeat(10) { sensor.activate(sensorActive(instance, npc("near$it", z = 2.0))) }
        repeat(1_000) { sensor.activate(sensorActive(instance, npc("far$it", z = 10_000.0))) }

        sensor.update(listOf(player))

        assertEquals(10, sensor.visitedNpcCount())
    }

    @Test
    fun `overlapping bounds choose ray distance then local id`() {
        val instance = instance()
        val player = player(PLAYER_ONE, Pos(0.0, 0.0, 0.0))
        val farFirst = sensorActive(instance, npc("a-far", z = 3.0))
        val nearLast = sensorActive(instance, npc("z-near", z = 2.0))
        val firstSensor =
            sensor(instance).also {
                it.activate(farFirst)
                it.activate(nearLast)
            }

        assertEquals(
            listOf(transition(PLAYER_ONE, "z-near", SceneTrigger.HOVER_ENTER)),
            firstSensor.update(listOf(player)),
        )

        val b = sensorActive(instance, npc("b", z = 2.0))
        val a = sensorActive(instance, npc("a", z = 2.0))
        val secondSensor =
            sensor(instance).also {
                it.activate(b)
                it.activate(a)
            }
        assertEquals(
            listOf(transition(PLAYER_ONE, "a", SceneTrigger.HOVER_ENTER)),
            secondSensor.update(listOf(player)),
        )
    }

    @Test
    fun `hover emits enter once and leave when aim changes`() {
        val instance = instance()
        val player = player(PLAYER_ONE, Pos(0.0, 0.0, 0.0))
        val active = sensorActive(instance, npc("guide", z = 2.0))
        val sensor = sensor(instance).also { it.activate(active) }

        assertEquals(
            listOf(transition(PLAYER_ONE, "guide", SceneTrigger.HOVER_ENTER)),
            sensor.update(listOf(player)),
        )
        assertEquals(emptyList(), sensor.update(listOf(player)))

        player.move(Pos(0.0, 0.0, 0.0, 180f, 0f))
        assertEquals(
            listOf(transition(PLAYER_ONE, "guide", SceneTrigger.HOVER_LEAVE)),
            sensor.update(listOf(player)),
        )
    }

    @Test
    fun `proximity hysteresis survives inactive npc without duplicate enter`() {
        val instance = instance()
        val player =
            player(PLAYER_ONE, Pos(2.0, 0.0, 0.0)).also {
                it.setInstance(instance, it.position).join()
            }
        val active =
            sensorActive(
                instance,
                npc("guide", proximity = ProximitySensor(3.0, 4.0), boundsY = 8.0),
            )
        val sensor = sensor(instance).also { it.activate(active) }

        assertEquals(
            listOf(transition(PLAYER_ONE, "guide", SceneTrigger.PROXIMITY_ENTER)),
            sensor.update(listOf(player)),
        )
        player.move(Pos(3.5, 0.0, 0.0))
        assertEquals(emptyList(), sensor.update(listOf(player)))
        sensor.deactivate(active.elementId)
        assertEquals(emptyList(), sensor.update(listOf(player)))

        player.move(Pos(2.0, 0.0, 0.0))
        sensor.activate(active)
        assertEquals(emptyList(), sensor.update(listOf(player)))
        player.move(Pos(4.1, 0.0, 0.0))
        assertEquals(
            listOf(transition(PLAYER_ONE, "guide", SceneTrigger.PROXIMITY_LEAVE)),
            sensor.update(listOf(player)),
        )
    }

    @Test
    fun `disconnect cleanup removes only that player's sensor and visual state`() {
        val instance = instance()
        val first =
            player(PLAYER_ONE, Pos(0.0, 0.0, 0.0)).also {
                it.setInstance(instance, it.position).join()
            }
        val active =
            sensorActive(instance, npc("guide", z = 2.0, proximity = ProximitySensor(3.0, 4.0)))
        val sensor = sensor(instance).also { it.activate(active) }
        sensor.update(listOf(first))

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
    fun `sensor visits only active cells near each player`() {
        val instance = instance()
        val player = player(PLAYER_ONE, Pos.ZERO)
        val sensor = sensor(instance)
        repeat(10) { sensor.activate(sensorActive(instance, npc("near$it", z = 2.0))) }
        repeat(1_000) { sensor.activate(sensorActive(instance, npc("far$it", z = 10_000.0))) }

        sensor.update(listOf(player))

        assertEquals(10, sensor.visitedNpcCount())
    }

    @Test
    fun `invisible npc emits proximity but can never hover`() {
        val instance = instance()
        val player =
            player(PLAYER_ONE, Pos.ZERO).also { it.setInstance(instance, it.position).join() }
        val invisible =
            sensorActive(
                instance,
                npc("hidden", z = 2.0, proximity = ProximitySensor(3.0, 4.0), visible = false),
            )
        val sensor = sensor(instance).also { it.activate(invisible) }

        assertEquals(
            listOf(transition(PLAYER_ONE, "hidden", SceneTrigger.PROXIMITY_ENTER)),
            sensor.update(listOf(player)),
        )
    }

    @Test
    fun `viewer clear isolates handle failures and still attempts every handle`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player = player(PLAYER_ONE, Pos.ZERO).also { it.setInstance(instance, Pos.ZERO).join() }
        val failing = RecordingHandle().also { it.clearFailure = IllegalStateException("first") }
        val later = RecordingHandle()
        val active = ActiveElement(LocalId("prop"), 1L, listOf(failing, later), null)
        val viewers = ViewerStateStore()
        val key = ViewerElementKey(player.uuid, active.elementId)

        assertFailsWith<IllegalStateException> { viewers.clear(instance, active, key) }

        assertTrue(failing.cleared)
        assertTrue(later.cleared)
    }

    @Test
    fun `completed viewer transition sends one final update then retires`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player = player(PLAYER_ONE, Pos.ZERO).also { it.setInstance(instance, Pos.ZERO).join() }
        val clock = ManualClock()
        val handle = RecordingHandle()
        val active = ActiveElement(LocalId("prop"), 1L, listOf(handle), null)
        val viewers = ViewerStateStore(clock)
        val key = ViewerElementKey(player.uuid, active.elementId)
        viewers.setScale(key, 2.0, 100)

        clock.advanceMillis(100)
        repeat(3) {
            viewers.applyTransitions(instance, mapOf(active.elementId to active)) { _, error ->
                throw error
            }
        }

        assertEquals(1, handle.viewerUpdates.size)
        assertEquals(2.0, handle.viewerUpdates.single().scaleMultiplier)
        assertNull(viewers.visualState(key).scaleTransition)
    }

    @Test
    fun `elapsed viewer state read retains transition ownership until final pump update`() {
        MinecraftServer.init()
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player = player(PLAYER_ONE, Pos.ZERO).also { it.setInstance(instance, Pos.ZERO).join() }
        val clock = ManualClock()
        val handle = RecordingHandle()
        val active = ActiveElement(LocalId("prop"), 1L, listOf(handle), null)
        val viewers = ViewerStateStore(clock)
        val key = ViewerElementKey(player.uuid, active.elementId)
        viewers.setScale(key, 2.0, 100)

        clock.advanceMillis(100)
        assertEquals(
            2.0,
            viewers.effectiveState(player.uuid, active.elementId, null).scaleMultiplier,
        )
        assertEquals(1, viewers.activeTransitionCount())

        viewers.applyTransitions(instance, mapOf(active.elementId to active)) { _, error ->
            throw error
        }
        assertEquals(listOf(2.0), handle.viewerUpdates.map { it.scaleMultiplier })
        assertEquals(0, viewers.activeTransitionCount())

        viewers.applyTransitions(instance, mapOf(active.elementId to active)) { _, error ->
            throw error
        }
        assertEquals(listOf(2.0), handle.viewerUpdates.map { it.scaleMultiplier })
    }

    @Test
    fun `zero duration viewer state never enters transition pump`() {
        val viewers = ViewerStateStore(ManualClock())
        val key = ViewerElementKey(PLAYER_ONE, LocalId("prop"))

        viewers.setScale(key, 2.0, 0)
        viewers.setHighlight(key, true, 0)

        assertEquals(0, viewers.activeTransitionCount())
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

    @Test
    fun `look rotation owns runtime transform and updates renderer exact bounds label and proxy`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val clock = ManualClock()
        val player = player(PLAYER_ONE, Pos(5.0, 0.0, 0.0))
        val npc =
            Npc(
                LocalId("guide"),
                null,
                Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                body = AssetKey("test:npc"),
                label = net.kyori.adventure.text.Component.text("Guide"),
                labelOffset = Vec3(2.0, 3.0, 0.0),
                look = LookBehavior.TrackNearest(20.0, yawOnly = true, 90.0),
                initialAnimation = null,
                interactionBounds = LocalBounds(Vec3(1.0, 1.0, 0.0), Vec3(4.0, 2.0, 1.0)),
                proximity = null,
                bindings = emptyList(),
            )
        val label = Entity(EntityType.TEXT_DISPLAY)
        val interaction = Entity(EntityType.INTERACTION)
        label.setInstance(instance, Pos.ZERO).join()
        interaction.setInstance(instance, Pos.ZERO).join()
        val handle = RecordingHandle()
        val active =
            ActiveElement(npc.id, 1L, listOf(handle), NpcPlatformEntities(label, interaction), npc)
        val look = LookController(clock, AlwaysEligible)

        look.update(listOf(player), listOf(active))
        clock.advanceSeconds(1)
        look.update(listOf(player), listOf(active))

        assertEquals(EulerRotation(-90.0, 0.0, 0.0), active.currentTransform.root.rotation)
        assertEquals(active.currentTransform, handle.transforms.last())
        assertEquals(0.0, label.position.x(), 0.000_001)
        assertEquals(3.0, label.position.y(), 0.000_001)
        assertEquals(2.0, label.position.z(), 0.000_001)
        assertEquals(0.0, interaction.position.x(), 0.000_001)
        assertEquals(0.0, interaction.position.y(), 0.000_001)
        assertEquals(1.0, interaction.position.z(), 0.000_001)
        assertEquals(4.0f, (interaction.entityMeta as InteractionMeta).width)
        assertEquals(2.0f, (interaction.entityMeta as InteractionMeta).height)

        val raycaster = BoundsRaycaster()
        val eye = Vec3(2.0, 1.0, -3.0)
        val direction = Vec3(0.0, 0.0, 1.0)
        assertNotNull(raycaster.rayDistance(npc, eye, direction, 5.0))
        assertNull(raycaster.rayDistance(active, eye, direction, 5.0))
    }

    private fun sensor(instance: net.minestom.server.instance.Instance) =
        NpcSensorEngine(instance, AlwaysEligible)

    private fun sensorActive(
        instance: net.minestom.server.instance.Instance,
        npc: Npc,
    ): ActiveElement {
        val interaction = Entity(EntityType.INTERACTION)
        interaction
            .setInstance(instance, npc.transform.position.let { Pos(it.x, it.y, it.z) })
            .join()
        return ActiveElement(npc.id, 1L, emptyList(), NpcPlatformEntities(null, interaction), npc)
    }

    private fun instance() =
        MinecraftServer.init().let {
            MinecraftServer.getInstanceManager().createInstanceContainer()
        }

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
        visible: Boolean = true,
        bounds: LocalBounds = LocalBounds(Vec3(0.0, boundsY, 0.0), Vec3(1.0, 1.0, 1.0)),
    ) =
        Npc(
            LocalId(id),
            null,
            Transform(Vec3(0.0, 0.0, z), rotation, Vec3(1.0, 1.0, 1.0)),
            visible = visible,
            body = AssetKey("test:npc"),
            label = null,
            labelOffset = Vec3(0.0, 0.0, 0.0),
            look = look,
            initialAnimation = null,
            interactionBounds = bounds,
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

        fun advanceMillis(millis: Long) {
            now += millis * 1_000_000L
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
        val transforms = mutableListOf<SceneRenderTransform>()
        val viewerUpdates = mutableListOf<SceneViewerVisualState>()
        var cleared = false
        var clearFailure: Throwable? = null

        override fun applyTransform(transform: SceneRenderTransform) {
            transforms += transform
            rotations += transform.root.rotation
        }

        override fun applyViewerState(player: Player, state: SceneViewerVisualState) {
            viewerUpdates += state
        }

        override fun clearViewerState(player: Player) {
            cleared = true
            clearFailure?.let { throw it }
        }

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
