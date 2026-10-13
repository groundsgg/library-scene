package gg.grounds.scene.minestom.internal.collision

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.ScenePlayerPolicy
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import gg.grounds.scene.minestom.internal.runtime.NpcPlatformEntities
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.minestom.server.MinecraftServer
import net.minestom.server.collision.BoundingBox
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.listener.PlayerPositionListener
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket
import net.minestom.server.network.packet.client.play.ClientTeleportConfirmPacket
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.play.EntityVelocityPacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class NpcCollisionControllerTest {
    @Test
    fun `native movement reapproach during cooldown does not latch an undelivered contact forever`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, 6.0))
        fixture.enableNativeMovement()
        fixture.protocolMove(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()
        assertEquals(1, fixture.velocityPackets().size)
        assertEquals(-0.3, fixture.velocityPackets().single().velocity().z(), 1e-9)

        // The real listener ignores even outward movement until correction is acknowledged.
        fixture.protocolMove(Pos(0.0, 0.0, -3.0))
        assertEquals(-2.0, fixture.player.position.z())
        fixture.confirmTeleport()
        fixture.protocolMove(Pos(0.0, 0.0, -3.0))
        assertEquals(-3.0, fixture.player.position.z())

        fixture.clock.advanceMillis(100)
        fixture.protocolMove(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()
        assertEquals(1, fixture.velocityPackets().size)
        fixture.confirmTeleport()
        fixture.clock.advanceMillis(150)
        fixture.protocolMove(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()
        assertEquals(2, fixture.velocityPackets().size)

        // Delivered continuous contact still cannot stack another impulse.
        fixture.confirmTeleport()
        fixture.clock.advanceMillis(250)
        fixture.protocolMove(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()
        assertEquals(2, fixture.velocityPackets().size)
    }

    @Test
    fun `fractional distance uses one soft horizontal impulse and preserves vertical velocity`() {
        listOf(0.5 to 4.54, 1.5 to 13.62, 0.0 to 0.0).forEach { (distance, speed) ->
            val fixture = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, 6.0, distance))
            fixture.player.velocity = Vec(0.0, 3.5, 0.0)
            fixture.connection.packets.clear()
            val event = fixture.move(Pos(0.0, 0.0, 2.0))
            fixture.runScheduled()
            assertEquals(Pos(0.0, 0.0, -2.0), event.newPosition)
            assertEquals(-speed, fixture.player.velocity.z(), 1e-9)
            assertEquals(3.5, fixture.player.velocity.y(), 1e-9)
            if (distance > 0.0) {
                val packet = fixture.velocityPackets().single()
                assertEquals(-speed / 20.0, packet.velocity().z(), 1e-9)
                assertEquals(3.5 / 20.0, packet.velocity().y(), 1e-9)
            } else assertTrue(fixture.velocityPackets().isEmpty())
        }
    }

    @Test
    fun `failed queued impulse can retry the same contact without stacking tasks`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, 6.0))
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.move(Pos(0.0, 0.0, 2.0))
        assertEquals(1, fixture.scheduled.size)
        fixture.translateCollider(10.0)
        fixture.runScheduled()
        assertEquals(Vec.ZERO, fixture.player.velocity)
        fixture.translateCollider(0.0)
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.move(Pos(0.0, 0.0, 2.0))
        assertEquals(1, fixture.scheduled.size)
        fixture.runScheduled()
        assertEquals(-6.0, fixture.player.velocity.z(), 1e-9)
    }

    @Test
    fun `none accepts movement without consulting player eligibility`() {
        val fixture = Fixture(NpcCollision())
        fixture.policy.eligible = false

        val event = fixture.move(Pos(0.0, 0.0, 4.0, 31f, -12f))

        assertEquals(Pos(0.0, 0.0, 4.0, 31f, -12f), event.newPosition)
        assertEquals(0, fixture.policy.reads)
    }

    @Test
    fun `solid blocks crossing and preserves requested view rotation`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0))

        val event = fixture.move(Pos(0.0, 0.0, 2.0, 73f, -18f))

        assertEquals(Pos(0.0, 0.0, -2.0, 73f, -18f), event.newPosition)
        assertEquals(0, fixture.scheduled.size)
    }

    @Test
    fun `native sweep blocks a fast crossing whose destination is beyond the npc`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0))

        val event = fixture.move(Pos(0.0, 0.0, 20.0))

        assertEquals(Pos(0.0, 0.0, -2.0), event.newPosition)
    }

    @Test
    fun `over-budget movement is corrected without resetting contact or scheduling bounce`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, 6.0))
        fixture.move(Pos(0.0, 0.0, 2.0))
        assertEquals(1, fixture.scheduled.size)

        val event = fixture.move(Pos(0.0, 0.0, -111.0, 28f, -9f))

        assertEquals(Pos(0.0, 0.0, -2.0, 28f, -9f), event.newPosition)
        assertEquals(1, fixture.scheduled.size)
        fixture.runScheduled()
        assertEquals(-6.0, fixture.player.velocity.z(), 1e-9)
    }

    @Test
    fun `tracker query reaches the top of a tall bottom-anchored collider`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0), colliderHeight = 20.0)
        fixture.player.teleport(Pos(0.0, 18.0, -2.0)).join()

        val event = fixture.move(Pos(0.0, 18.0, 2.0))

        assertEquals(Pos(0.0, 18.0, -2.0), event.newPosition)
    }

    @Test
    fun `moving away and initial overlap escape are accepted`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0))
        fixture.player.teleport(Pos(0.0, 0.0, -1.31)).join()
        val away = fixture.move(Pos(0.0, 0.0, -2.0))
        assertEquals(Pos(0.0, 0.0, -2.0), away.newPosition)

        fixture.player.teleport(Pos(0.0, 0.0, 0.0)).join()
        val escape = fixture.move(Pos(0.0, 0.0, 2.0))
        assertEquals(Pos(0.0, 0.0, 2.0), escape.newPosition)
    }

    @Test
    fun `centered downward crossing remains solid and has deterministic horizontal bounce`() {
        val solid = Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0))
        solid.player.teleport(Pos(0.0, 3.0, 0.0)).join()
        assertEquals(Pos(0.0, 3.0, 0.0), solid.move(Pos(0.0, 1.0, 0.0)).newPosition)

        val bounce = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, 6.0))
        bounce.player.teleport(Pos(0.0, 3.0, 0.0)).join()
        bounce.player.velocity = Vec(4.0, -2.5, 3.0)
        val event = bounce.move(Pos(0.0, 1.0, 0.0))
        bounce.runScheduled()

        assertEquals(Pos(0.0, 3.0, 0.0), event.newPosition)
        assertEquals(Vec(-6.0, -2.5, 0.0), bounce.player.velocity)
    }

    @Test
    fun `tangent and vertical-clearance movement remain accepted while near-boundary inward movement blocks`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0))
        fixture.player.teleport(Pos(-1.31, 0.0, -2.0)).join()
        assertEquals(Pos(-1.31, 0.0, 2.0), fixture.move(Pos(-1.31, 0.0, 2.0)).newPosition)

        fixture.player.teleport(Pos(0.0, 3.1, -2.0)).join()
        assertEquals(Pos(0.0, 3.1, 2.0), fixture.move(Pos(0.0, 3.1, 2.0)).newPosition)

        fixture.player.teleport(Pos(0.0, 0.0, -1.31)).join()
        assertEquals(Pos(0.0, 0.0, -1.31), fixture.move(Pos(0.0, 0.0, -1.29)).newPosition)
    }

    @Test
    fun `hidden unowned spectator ineligible cancelled and rotation-only moves are ignored`() {
        assertAccepted(Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0), visible = false))
        assertAccepted(Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0), owned = false))

        Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0)).also {
            it.player.gameMode = GameMode.SPECTATOR
            assertAccepted(it)
        }
        Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0)).also {
            it.policy.eligible = false
            assertAccepted(it)
        }
        Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0)).also {
            val event = PlayerMoveEvent(it.player, Pos(0.0, 0.0, 2.0), false)
            event.isCancelled = true
            it.controller.move(event)
            assertEquals(Pos(0.0, 0.0, 2.0), event.newPosition)
        }
        Fixture(NpcCollision(NpcCollisionMode.SOLID, 6.0)).also {
            val event = it.move(Pos(0.0, 0.0, -2.0, 40f, 20f))
            assertEquals(Pos(0.0, 0.0, -2.0, 40f, 20f), event.newPosition)
            assertEquals(0, it.policy.reads)
        }
    }

    @Test
    fun `bounce strengths set exact horizontal speed retain vertical velocity and zero still blocks`() {
        listOf(0.0, 6.0, 12.0).forEach { strength ->
            val fixture = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, strength))
            fixture.player.velocity = Vec(2.0, 3.5, 4.0)

            val event = fixture.move(Pos(0.0, 0.0, 2.0))
            fixture.runScheduled()

            assertEquals(Pos(0.0, 0.0, -2.0), event.newPosition)
            if (strength == 0.0) {
                assertEquals(Vec(2.0, 3.5, 4.0), fixture.player.velocity)
            } else {
                assertEquals(0.0, fixture.player.velocity.x(), 1e-9)
                assertEquals(-strength, fixture.player.velocity.z(), 1e-9)
                assertEquals(3.5, fixture.player.velocity.y(), 1e-9)
                assertEquals(strength, fixture.player.velocity.horizontalLength(), 1e-9)
            }
        }
    }

    @Test
    fun `continuous contact emits one impulse and genuine reapproach after 250ms emits another`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, 6.0))

        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()
        fixture.player.velocity = Vec.ZERO
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()
        assertEquals(Vec.ZERO, fixture.player.velocity)

        fixture.player.teleport(Pos(0.0, 0.0, -2.0)).join()
        fixture.move(Pos(0.0, 0.0, -3.0))
        fixture.clock.advanceMillis(250)
        fixture.player.teleport(Pos(0.0, 0.0, -2.0)).join()
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()

        assertEquals(-6.0, fixture.player.velocity.z(), 1e-9)
    }

    @Test
    fun `unchanged collider refresh retains continuous contact suppression`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, 6.0))
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()
        fixture.player.velocity = Vec.ZERO
        fixture.clock.advanceMillis(250)

        fixture.refreshCollider()
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()

        assertEquals(Vec.ZERO, fixture.player.velocity)
    }

    @Test
    fun `yaw-changing collider revalidates queued bounce without resetting continuous contact`() {
        val fixture =
            Fixture(
                NpcCollision(NpcCollisionMode.BOUNCE, 6.0),
                colliderWidth = 4.0,
                colliderDepth = 1.0,
            )
        fixture.move(Pos(0.0, 0.0, 2.0))

        fixture.rotateCollider(15.0)
        fixture.runScheduled()
        assertEquals(6.0, fixture.player.velocity.horizontalLength(), 1e-9)

        fixture.player.velocity = Vec.ZERO
        fixture.clock.advanceMillis(250)
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()

        assertEquals(Vec.ZERO, fixture.player.velocity)
    }

    @Test
    fun `bounce cooldown is measured between delivered impulses`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, 6.0))
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.clock.advanceMillis(200)
        fixture.runScheduled()
        assertEquals(-6.0, fixture.player.velocity.z(), 1e-9)

        fixture.player.velocity = Vec.ZERO
        fixture.move(Pos(0.0, 0.0, -3.0))
        fixture.clock.advanceMillis(50)
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.clock.advanceMillis(50)
        fixture.runScheduled()

        assertEquals(Vec.ZERO, fixture.player.velocity)
    }

    @Test
    fun `cancelled queued bounce does not consume cooldown`() {
        val fixture = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, 6.0))
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.translateCollider(10.0)
        fixture.runScheduled()
        assertEquals(Vec.ZERO, fixture.player.velocity)

        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.translateCollider(0.0)
        fixture.move(Pos(0.0, 0.0, 2.0))
        fixture.runScheduled()

        assertEquals(-6.0, fixture.player.velocity.z(), 1e-9)
    }

    @Test
    fun `disconnect close and collider generation change invalidate delayed impulses`() {
        listOf<(Fixture) -> Unit>(
                { it.controller.disconnect(it.player.uuid) },
                { it.controller.close() },
                { it.replaceCollider() },
            )
            .forEach { invalidate ->
                val fixture = Fixture(NpcCollision(NpcCollisionMode.BOUNCE, 6.0))
                fixture.player.velocity = Vec(1.0, 2.0, 3.0)
                fixture.move(Pos(0.0, 0.0, 2.0))

                invalidate(fixture)
                fixture.runScheduled()

                assertEquals(Vec(1.0, 2.0, 3.0), fixture.player.velocity)
            }
    }

    private fun assertAccepted(fixture: Fixture) {
        val requested = Pos(0.0, 0.0, 2.0)
        assertEquals(requested, fixture.move(requested).newPosition)
        assertTrue(fixture.scheduled.isEmpty())
    }

    private class Fixture(
        collision: NpcCollision,
        visible: Boolean = true,
        owned: Boolean = true,
        private val colliderWidth: Double = 2.0,
        private val colliderHeight: Double = 2.0,
        private val colliderDepth: Double = 2.0,
    ) {
        val instance: InstanceContainer =
            MinecraftServer.getInstanceManager().createInstanceContainer()
        val connection = FakeConnection()
        val player =
            Player(connection, GameProfile(UUID.randomUUID(), "Alex")).also {
                it.setInstance(instance, Pos(0.0, 0.0, -2.0)).join()
            }
        val policy = CountingPolicy()
        val clock = MutableClock()
        val scheduled = ArrayDeque<Runnable>()
        private val active = mutableMapOf<LocalId, ActiveElement>()
        private val ownership = mutableMapOf<UUID, LocalId>()
        private var generation = 1L
        private val npc = npc(collision, visible)
        private var interaction = interaction()
        val controller =
            NpcCollisionController(
                instance,
                policy,
                clock,
                scheduled::addLast,
                ownership::get,
                active::get,
            )

        init {
            publish(owned)
        }

        fun move(destination: Pos): PlayerMoveEvent =
            PlayerMoveEvent(player, destination, false).also(controller::move)

        fun runScheduled() {
            while (scheduled.isNotEmpty()) scheduled.removeFirst().run()
        }

        fun enableNativeMovement() {
            player.refreshReceivedTeleportId(player.lastSentTeleportId)
            connection.packets.clear()
            instance.eventNode().addListener(PlayerMoveEvent::class.java, controller::move)
        }

        fun protocolMove(destination: Pos) =
            PlayerPositionListener.playerPositionListener(
                ClientPlayerPositionPacket(destination, true, false),
                player,
            )

        fun confirmTeleport() {
            // October adds confirmed position to this packet; keep the July compilation ABI.
            val constructor = ClientTeleportConfirmPacket::class.java.declaredConstructors.single()
            val packet =
                if (constructor.parameterCount == 1)
                    constructor.newInstance(player.lastSentTeleportId)
                else constructor.newInstance(player.lastSentTeleportId, player.position)
            PlayerPositionListener.teleportConfirmListener(
                packet as ClientTeleportConfirmPacket,
                player,
            )
        }

        fun velocityPackets() = connection.packets.filterIsInstance<EntityVelocityPacket>()

        fun replaceCollider() {
            val previous = active.getValue(npc.id)
            controller.colliderRemoved(previous)
            active.clear()
            ownership.clear()
            interaction.remove()
            generation++
            interaction = interaction()
            publish(true)
        }

        fun refreshCollider() {
            controller.colliderChanged(active.getValue(npc.id))
        }

        fun rotateCollider(yaw: Double) {
            transformCollider(0.0, yaw)
        }

        fun translateCollider(x: Double) {
            transformCollider(x, 0.0)
        }

        private fun transformCollider(x: Double, yaw: Double) {
            active
                .getValue(npc.id)
                .updateRuntimeTransform(
                    SceneRenderTransform(
                        Transform(
                            Vec3(x, 0.0, 0.0),
                            EulerRotation(yaw, 0.0, 0.0),
                            Vec3(1.0, 1.0, 1.0),
                        ),
                        null,
                    )
                )
        }

        private fun publish(owned: Boolean) {
            val element =
                ActiveElement(
                    npc.id,
                    generation,
                    emptyList(),
                    NpcPlatformEntities(null, interaction),
                    npc,
                    initialTransform = SceneRenderTransform(npc.transform, null),
                    colliderChanged = controller::colliderChanged,
                )
            active[npc.id] = element
            if (owned) ownership[interaction.uuid] = npc.id
            controller.colliderChanged(element)
        }

        private fun interaction() =
            Entity(EntityType.INTERACTION).also {
                it.boundingBox = BoundingBox(colliderWidth, colliderHeight, colliderDepth)
                it.setInstance(instance, Pos(0.0, 0.0, 0.0)).join()
            }

        private fun npc(collision: NpcCollision, visible: Boolean) =
            Npc(
                id = LocalId("guide"),
                group = null,
                transform =
                    Transform(
                        Vec3(0.0, 0.0, 0.0),
                        EulerRotation(0.0, 0.0, 0.0),
                        Vec3(1.0, 1.0, 1.0),
                    ),
                visible = visible,
                activation = ActivationPolicy.ALWAYS,
                body = AssetKey("test:npc"),
                label = null,
                labelOffset = Vec3(0.0, 0.0, 0.0),
                look = LookBehavior.Fixed,
                initialAnimation = null,
                interactionBounds =
                    LocalBounds(
                        Vec3(0.0, colliderHeight / 2.0, 0.0),
                        Vec3(colliderWidth, colliderHeight, colliderDepth),
                    ),
                proximity = null,
                bindings = emptyList(),
                collision = collision,
            )
    }

    private class CountingPolicy : ScenePlayerPolicy {
        var eligible = true
        var reads = 0

        override fun isEligible(player: Player): Boolean {
            reads++
            return eligible
        }

        override fun hasPermission(player: Player, permission: String) = true
    }

    private class MutableClock : SceneClock {
        private var nanos = 0L

        override fun nanoTime() = nanos

        fun advanceMillis(millis: Long) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis)
        }
    }

    private class FakeConnection : PlayerConnection() {
        val packets = mutableListOf<SendablePacket>()

        override fun sendPacket(packet: SendablePacket) {
            packets.add(packet)
        }

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    private fun Vec.horizontalLength() = kotlin.math.sqrt(x() * x() + z() * z())

    companion object {
        @JvmStatic
        @BeforeAll
        fun initializeMinestom() {
            MinecraftServer.init()
        }
    }
}
