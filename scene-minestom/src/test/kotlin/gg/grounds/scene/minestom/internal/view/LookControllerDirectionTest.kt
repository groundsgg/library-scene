package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import gg.grounds.scene.minestom.internal.geometry.affine
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import gg.grounds.scene.minestom.internal.runtime.NpcPlatformEntities
import java.net.InetSocketAddress
import java.util.UUID
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.*
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LookControllerDirectionTest {
    @Test
    fun `head follows both axes while body stays upright and follows beyond sixty degrees`() {
        MinecraftServer.init()
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player = Player(Connection(), GameProfile(UUID.randomUUID(), "Target"))
        player.setInstance(instance, Pos(2.5, 0.0, 4.330127018922193)).join()
        var now = 0L
        val handle = HeadHandle()
        val npc =
            Npc(
                LocalId("head-guide"),
                null,
                Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                body = AssetKey("test:body"),
                label = Component.empty(),
                labelOffset = Vec3(0.0, 2.0, 0.0),
                look = LookBehavior.TrackNearest(32.0, false, 180.0),
                initialAnimation = null,
                interactionBounds = LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                proximity = null,
                bindings = emptyList(),
            )
        val active =
            ActiveElement(
                npc.id,
                1,
                listOf(handle),
                NpcPlatformEntities(null, Entity(EntityType.INTERACTION)),
                npc,
            )
        val controller =
            LookController(
                SceneClock { now },
                object : ScenePlayerPolicy {
                    override fun isEligible(player: Player) = true

                    override fun hasPermission(player: Player, permission: String) = true
                },
            )
        fun step(position: Pos) {
            player.teleport(position).join()
            now += 1_000_000_000L
            controller.update(listOf(player), listOf(active))
        }
        try {
            controller.update(listOf(player), listOf(active))
            step(player.position)
            assertEquals(
                0.0,
                active.currentTransform.root.rotation.yaw,
                1e-6,
                "Body must stay still inside the head range",
            )
            assertEquals(30.0, handle.yaw, 1e-5)
            assertEquals(0.0, handle.pitch, 1e-5, "Aim originates at the head, not the feet")
            step(Pos(3.5355339059, 0.0, -3.5355339059))
            assertEquals(75.0, active.currentTransform.root.rotation.yaw, 1e-5)
            assertEquals(60.0, handle.yaw, 1e-5)
            step(Pos(3.5355339059, 5.0, -3.5355339059))
            assertEquals(0.0, active.currentTransform.root.rotation.pitch, 1e-8)
            assertEquals(-45.0, handle.pitch, 1e-5, "Only the head looks upward")
            assertEquals(npc.interactionBounds, active.npc!!.interactionBounds)
            assertEquals(0.0, active.currentTransform.root.rotation.roll, 1e-8)
            step(Pos(-3.5355339059, 0.0, -3.5355339059))
            assertEquals(
                60.0,
                handle.yaw,
                1e-5,
                "Shortest turn crosses the yaw seam without reversing",
            )
        } finally {
            active.close()
            player.remove()
            MinecraftServer.getInstanceManager().unregisterInstance(instance)
        }
    }

    private class HeadHandle : HeadLookRenderedAssetHandle {
        override val headLookOrigin = Vec3(0.0, 1.62, 0.0)
        var yaw = 0.0
        var pitch = 0.0

        override fun applyHeadLook(yawDegrees: Double, pitchDegrees: Double) {
            yaw = yawDegrees
            pitch = pitchDegrees
        }

        override fun applyTransform(transform: SceneRenderTransform) {}

        override fun applyViewerState(player: Player, state: SceneViewerVisualState) {}

        override fun clearViewerState(player: Player) {}

        override fun startAnimation(animation: LocalId, elapsedMillis: Long) {}

        override fun stopAnimation(animation: LocalId?) {}

        override fun advanceAnimation(elapsedMillis: Long) {}

        override fun close() {}
    }

    @Test
    fun `tracked Scene forward points towards an east target instead of away`() {
        MinecraftServer.init()
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val player = Player(Connection(), GameProfile(UUID.randomUUID(), "Target"))
        player.setInstance(instance, Pos(5.0, 0.0, 0.0)).join()
        var now = 0L
        val npc =
            Npc(
                LocalId("guide"),
                null,
                Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                body = AssetKey("test:body"),
                label = Component.empty(),
                labelOffset = Vec3(0.0, 2.0, 0.0),
                look = LookBehavior.TrackNearest(16.0, true, 180.0),
                initialAnimation = null,
                interactionBounds = LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                proximity = null,
                bindings = emptyList(),
            )
        val active =
            ActiveElement(
                npc.id,
                1,
                emptyList(),
                NpcPlatformEntities(null, Entity(EntityType.INTERACTION)),
                npc,
            )
        val controller =
            LookController(
                SceneClock { now },
                object : ScenePlayerPolicy {
                    override fun isEligible(player: Player) = true

                    override fun hasPermission(player: Player, permission: String) = true
                },
            )
        try {
            controller.update(listOf(player), listOf(active))
            now = 1_000_000_000L
            controller.update(listOf(player), listOf(active))
            val forward = active.currentTransform.affine().transformVector(Vec3(0.0, 0.0, 1.0))
            assertTrue(
                forward.x > 0.99,
                "Rendered Scene forward must face the target at +X, got $forward",
            )
            assertEquals(0.0, forward.z, 1e-6)
        } finally {
            active.close()
            player.remove()
            MinecraftServer.getInstanceManager().unregisterInstance(instance)
        }
    }

    private class Connection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) {}

        override fun getRemoteAddress() = InetSocketAddress(0)
    }
}
