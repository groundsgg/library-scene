package gg.grounds.scene.npc.minestom

import gg.grounds.npc.core.NpcTransform
import gg.grounds.npc.core.NpcVector3
import gg.grounds.npc.minestom.NpcRenderTransform
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneRenderTransform

object SceneNpcTransformMapper {
    @JvmStatic
    fun toNpc(transform: SceneRenderTransform) =
        NpcRenderTransform(toNpc(transform.root), transform.local?.let(::toNpc))

    @JvmStatic
    fun toNpc(transform: Transform) =
        NpcTransform(
            toNpc(transform.position),
            NpcVector3(transform.rotation.pitch, transform.rotation.yaw, transform.rotation.roll),
            toNpc(transform.scale),
        )

    @JvmStatic fun toNpc(vector: Vec3) = NpcVector3(vector.x, vector.y, vector.z)

    @JvmStatic fun toScene(vector: NpcVector3) = Vec3(vector.x, vector.y, vector.z)
}
