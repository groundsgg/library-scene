package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.interaction.SceneBoundsRaycaster
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.runtime.ActiveElement

internal class BoundsRaycaster {
    fun rayDistance(npc: Npc, eye: Vec3, direction: Vec3, maximumDistance: Double): Double? =
        distance(npc, SceneRenderTransform(npc.transform, null), eye, direction, maximumDistance)

    fun rayDistance(
        active: ActiveElement,
        eye: Vec3,
        direction: Vec3,
        maximumDistance: Double,
    ): Double? {
        val npc = active.npc ?: return null
        return distance(
            npc,
            active.transformOr(SceneRenderTransform(npc.transform, null)),
            eye,
            direction,
            maximumDistance,
        )
    }

    private fun distance(
        npc: Npc,
        transform: SceneRenderTransform,
        eye: Vec3,
        direction: Vec3,
        maximumDistance: Double,
    ): Double? =
        SceneBoundsRaycaster.distance(
            npc.interactionBounds,
            transform.root,
            transform.local,
            eye,
            direction,
            maximumDistance,
        )
}
