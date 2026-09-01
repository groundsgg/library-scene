package gg.grounds.scene.minestom.internal.geometry

import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneRenderTransform
import kotlin.test.Test
import kotlin.test.assertEquals

class SceneTransformsTest {
    @Test
    fun `transforms all local bounds corners through root yaw pitch roll and scale`() {
        val transform = SceneRenderTransform(
            root = Transform(Vec3(-10.0, 5.0, -2.0), EulerRotation(90.0, 90.0, 90.0), Vec3(2.0, 3.0, 4.0)),
            local = null,
        )

        assertBounds(
            WorldBounds(Vec3(-12.0, 1.0, -5.0), Vec3(-8.0, 9.0, 1.0)),
            LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(2.0, 2.0, 2.0)).transformed(transform.affine()),
        )
    }

    @Test
    fun `composes a rotated local part after a non-uniform rotated root`() {
        val transform = SceneRenderTransform(
            root = Transform(Vec3(-10.0, 5.0, -2.0), EulerRotation(90.0, 0.0, 0.0), Vec3(2.0, 3.0, 4.0)),
            local = Transform(Vec3(1.0, 2.0, 3.0), EulerRotation(0.0, 0.0, 90.0), Vec3(1.0, 1.0, 1.0)),
        )

        assertBounds(
            WorldBounds(Vec3(-2.0, 11.0, -6.0), Vec3(6.0, 17.0, -2.0)),
            LocalBounds(Vec3(1.0, 0.0, 0.0), Vec3(2.0, 2.0, 2.0)).transformed(transform.affine()),
        )
    }

    private fun assertBounds(expected: WorldBounds, actual: WorldBounds) {
        assertVec(expected.min, actual.min)
        assertVec(expected.max, actual.max)
    }

    private fun assertVec(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.z, actual.z, 1e-9)
    }
}
