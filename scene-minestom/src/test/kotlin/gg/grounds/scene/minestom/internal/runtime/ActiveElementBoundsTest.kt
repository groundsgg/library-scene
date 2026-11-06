package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.geometry.WorldBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ActiveElementBoundsTest {
    @Test
    fun `unchanged geometry reuses world bounds across sensor refreshes and equal transforms`() {
        val element = active()
        val first = element.currentBounds()
        assertBounds(first, Vec3(0.0, 0.0, 0.0), Vec3(2.0, 2.0, 4.0))
        repeat(100) { assertSame(first, element.currentBounds()) }

        element.updateRuntimeTransform(
            SceneRenderTransform(element.currentTransform.root.copy(), null)
        )
        assertSame(first, element.currentBounds())
    }

    @Test
    fun `warm world bounds follow root translation rotation and scale immediately`() {
        val element = active()
        var previous = element.currentBounds()
        val cases =
            listOf(
                Triple(
                    transform(position = Vec3(10.0, 20.0, 30.0)),
                    Vec3(10.0, 20.0, 30.0),
                    Vec3(12.0, 22.0, 34.0),
                ),
                Triple(
                    transform(rotation = EulerRotation(90.0, 0.0, 0.0)),
                    Vec3(0.0, 0.0, -2.0),
                    Vec3(4.0, 2.0, 0.0),
                ),
                Triple(
                    transform(scale = Vec3(2.0, 3.0, 4.0)),
                    Vec3(0.0, 0.0, 0.0),
                    Vec3(4.0, 6.0, 16.0),
                ),
            )
        for ((root, min, max) in cases) {
            element.updateRuntimeTransform(SceneRenderTransform(root, null))
            val current = element.currentBounds()
            assertNotSame(previous, current)
            assertBounds(current, min, max)
            assertSame(current, element.currentBounds())
            previous = current
        }
    }

    @Test
    fun `warm world bounds follow local translation shear and nonuniform scale`() {
        val root = transform(scale = Vec3(2.0, 3.0, 4.0))
        val element = active()
        element.updateRuntimeTransform(SceneRenderTransform(root, null))
        var previous = element.currentBounds()
        val cases =
            listOf(
                Triple(
                    transform(position = Vec3(1.0, 2.0, 3.0)),
                    Vec3(2.0, 6.0, 12.0),
                    Vec3(6.0, 12.0, 28.0),
                ),
                Triple(
                    transform(rotation = EulerRotation(45.0, 0.0, 0.0)),
                    Vec3(0.0, 0.0, -5.656854249492381),
                    Vec3(8.48528137423857, 6.0, 11.31370849898476),
                ),
                Triple(
                    transform(scale = Vec3(3.0, 0.5, 2.0)),
                    Vec3(0.0, 0.0, 0.0),
                    Vec3(12.0, 3.0, 32.0),
                ),
            )
        for ((local, min, max) in cases) {
            element.updateRuntimeTransform(SceneRenderTransform(root, local))
            val current = element.currentBounds()
            assertNotSame(previous, current)
            assertBounds(current, min, max)
            assertSame(current, element.currentBounds())
            previous = current
        }
        element.updateRuntimeTransform(SceneRenderTransform(root, null))
        assertBounds(element.currentBounds(), Vec3(0.0, 0.0, 0.0), Vec3(4.0, 6.0, 16.0))
    }

    @Test
    fun `replacement and peer elements retain their own immutable authored bounds`() {
        val original = active()
        val first = original.currentBounds()
        val replacement =
            active(LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(2.0, 4.0, 6.0)), generation = 2L)
        val second = replacement.currentBounds()
        assertNotSame(first, second)
        assertBounds(second, Vec3(-1.0, -2.0, -3.0), Vec3(1.0, 2.0, 3.0))
        replacement.updateRuntimeTransform(
            SceneRenderTransform(transform(position = Vec3(30.0, 0.0, 0.0)), null)
        )
        replacement.currentBounds()
        assertSame(first, original.currentBounds())
        assertBounds(first, Vec3(0.0, 0.0, 0.0), Vec3(2.0, 2.0, 4.0))
        assertBounds(second, Vec3(-1.0, -2.0, -3.0), Vec3(1.0, 2.0, 3.0))
    }

    @Test
    fun `rejected solid geometry keeps the previous transform and warm world bounds`() {
        val element = active(collision = NpcCollision(NpcCollisionMode.SOLID, 6.0))
        val first = element.currentBounds()
        val initial = element.currentTransform
        assertFailsWith<IllegalArgumentException> {
            element.updateRuntimeTransform(
                SceneRenderTransform(transform(scale = Vec3(100.0, 100.0, 100.0)), null)
            )
        }
        assertEquals(initial, element.currentTransform)
        assertSame(first, element.currentBounds())
        assertBounds(first, Vec3(0.0, 0.0, 0.0), Vec3(2.0, 2.0, 4.0))
    }

    private fun active(
        bounds: LocalBounds = LocalBounds(Vec3(1.0, 1.0, 2.0), Vec3(2.0, 2.0, 4.0)),
        generation: Long = 1L,
        collision: NpcCollision = NpcCollision(),
    ): ActiveElement {
        val npc =
            Npc(
                LocalId("bounds"),
                null,
                transform(),
                body = AssetKey("test:npc"),
                label = null,
                labelOffset = Vec3(0.0, 0.0, 0.0),
                look = LookBehavior.Fixed,
                initialAnimation = null,
                interactionBounds = bounds,
                proximity = null,
                bindings = emptyList(),
                collision = collision,
            )
        return ActiveElement(npc.id, generation, emptyList(), null, npc)
    }

    private fun transform(
        position: Vec3 = Vec3(0.0, 0.0, 0.0),
        rotation: EulerRotation = EulerRotation(0.0, 0.0, 0.0),
        scale: Vec3 = Vec3(1.0, 1.0, 1.0),
    ) = Transform(position, rotation, scale)

    private fun assertBounds(bounds: WorldBounds, min: Vec3, max: Vec3) {
        assertEquals(min.x, bounds.min.x, 1e-9)
        assertEquals(min.y, bounds.min.y, 1e-9)
        assertEquals(min.z, bounds.min.z, 1e-9)
        assertEquals(max.x, bounds.max.x, 1e-9)
        assertEquals(max.y, bounds.max.y, 1e-9)
        assertEquals(max.z, bounds.max.z, 1e-9)
    }
}
