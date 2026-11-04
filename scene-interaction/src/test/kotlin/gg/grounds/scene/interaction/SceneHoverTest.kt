package gg.grounds.scene.interaction

import gg.grounds.scene.format.*
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class SceneHoverTest {
    private val zero = Vec3(0.0, 0.0, 0.0)
    private val bounds = LocalBounds(zero, Vec3(2.0, 2.0, 2.0))

    private fun transform(
        position: Vec3 = zero,
        yaw: Double = 0.0,
        scale: Vec3 = Vec3(1.0, 1.0, 1.0),
    ) = Transform(position, EulerRotation(yaw, 0.0, 0.0), scale)

    @Test
    fun `ray respects rotation scale local transform and world reach`() {
        val root = transform(Vec3(4.0, 0.0, 0.0), 90.0, Vec3(2.0, 1.0, 1.0))
        val local = transform(Vec3(0.0, 0.0, 1.0))
        val hit = SceneBoundsRaycaster.distance(bounds, root, local, zero, Vec3(2.0, 0.0, 0.0), 5.0)
        assertEquals(4.0, assertNotNull(hit), 1e-8)
        assertNull(
            SceneBoundsRaycaster.distance(bounds, root, local, zero, Vec3(1.0, 0.0, 0.0), 3.99)
        )
        assertNull(
            SceneBoundsRaycaster.distance(bounds, root, local, zero, Vec3(-1.0, 0.0, 0.0), 5.0)
        )
    }

    @Test
    fun `prepared bounds preserve world distance through nonuniform scale and local shear`() {
        val prepared =
            SceneBoundsRaycaster.prepare(
                bounds,
                transform(scale = Vec3(2.0, 1.0, 1.0)),
                transform(yaw = 45.0),
            )
        val eye = Vec3(0.0, 0.0, -5.0)
        assertEquals(
            5.0 - kotlin.math.sqrt(2.0),
            assertNotNull(prepared.distance(eye, Vec3(0.0, 0.0, 2.0), 5.0)),
            1e-8,
        )
        assertNull(prepared.distance(eye, Vec3(0.0, 0.0, 1.0), 3.5))
        assertEquals(0.0, prepared.distance(zero, Vec3(0.0, 0.0, 1.0), 5.0))
        assertFailsWith<IllegalArgumentException> { prepared.distance(eye, zero, 5.0) }
        assertFailsWith<IllegalArgumentException> {
            prepared.distance(eye, Vec3(0.0, 0.0, 1.0), Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `parallel slabs preserve inclusive faces signed zero and world reach on every axis`() {
        val prepared = SceneBoundsRaycaster.prepare(bounds, transform(), null)
        val cases =
            listOf(
                Vec3(-4.0, 0.0, 0.0) to Vec3(2.0, -0.0, 0.0),
                Vec3(4.0, 0.0, 0.0) to Vec3(-2.0, 0.0, -0.0),
                Vec3(0.0, -4.0, 0.0) to Vec3(-0.0, 2.0, 0.0),
                Vec3(0.0, 4.0, 0.0) to Vec3(0.0, -2.0, -0.0),
                Vec3(0.0, 0.0, -4.0) to Vec3(0.0, -0.0, 2.0),
                Vec3(0.0, 0.0, 4.0) to Vec3(-0.0, 0.0, -2.0),
                Vec3(1.0, 1.0, -4.0) to Vec3(0.0, -0.0, 2.0),
            )
        cases.forEach { (eye, direction) ->
            assertEquals(
                3.0.toRawBits(),
                assertNotNull(prepared.distance(eye, direction, 3.0)).toRawBits(),
            )
            assertNull(prepared.distance(eye, direction, Math.nextDown(3.0)))
        }
        assertNull(prepared.distance(Vec3(Math.nextUp(1.0), 0.0, -4.0), Vec3(-0.0, 0.0, 1.0), 5.0))
        assertNull(prepared.distance(Vec3(0.0, Math.nextUp(1.0), -4.0), Vec3(0.0, -0.0, 1.0), 5.0))
        assertNull(prepared.distance(Vec3(-4.0, 0.0, Math.nextUp(1.0)), Vec3(1.0, 0.0, -0.0), 5.0))
        assertNull(prepared.distance(Vec3(0.0, 0.0, 4.0), Vec3(0.0, 0.0, 1.0), 5.0))
    }

    @Test
    fun `prepared rays remain independent after inside face and missed queries`() {
        val prepared = SceneBoundsRaycaster.prepare(bounds, transform(), null)
        val other = SceneBoundsRaycaster.prepare(bounds, transform(Vec3(10.0, 0.0, 0.0)), null)
        repeat(3) {
            assertEquals(
                0.0.toRawBits(),
                assertNotNull(prepared.distance(zero, Vec3(1.0, 0.0, 0.0), 5.0)).toRawBits(),
            )
            assertEquals(
                0.0.toRawBits(),
                assertNotNull(prepared.distance(Vec3(1.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), 5.0))
                    .toRawBits(),
            )
            assertNull(prepared.distance(Vec3(2.0, 0.0, -4.0), Vec3(0.0, 0.0, 1.0), 5.0))
            assertEquals(9.0, other.distance(zero, Vec3(2.0, 0.0, 0.0), 10.0))
            assertEquals(3.0, prepared.distance(Vec3(0.0, 0.0, -4.0), Vec3(0.0, 0.0, 2.0), 5.0))
        }
    }

    @Test
    fun `prepared rays reject invalid reach and overflowing direction length`() {
        val prepared = SceneBoundsRaycaster.prepare(bounds, transform(), null)
        for (reach in
            listOf(
                0.0,
                -0.0,
                -1.0,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
            )) {
            assertFailsWith<IllegalArgumentException> {
                prepared.distance(zero, Vec3(1.0, 0.0, 0.0), reach)
            }
        }
        assertFailsWith<IllegalArgumentException> { prepared.distance(zero, zero, 5.0) }
        assertFailsWith<IllegalArgumentException> {
            prepared.distance(zero, Vec3(Double.MAX_VALUE, Double.MAX_VALUE, 0.0), 5.0)
        }
    }

    @Test
    fun `nearest tie and transitions stay independent per viewer and clean up`() {
        val selector = SceneHoverSelector()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val first = LocalId("a")
        val second = LocalId("b")
        val hits = listOf(SceneHoverHit(second, 2.0), SceneHoverHit(first, 2.0))
        assertEquals(
            listOf(SceneHoverTransition(a, first, SceneTrigger.HOVER_ENTER)),
            selector.update(a, hits),
        )
        assertEquals(emptyList(), selector.update(a, hits))
        selector.update(b, listOf(SceneHoverHit(second, 1.0)))
        assertEquals(
            listOf(SceneHoverTransition(a, first, SceneTrigger.HOVER_LEAVE)),
            selector.update(a, emptyList()),
        )
        assertEquals(second, selector.current(b))
        assertEquals(
            listOf(SceneHoverTransition(b, second, SceneTrigger.HOVER_LEAVE)),
            selector.removeViewer(b),
        )
        selector.update(a, hits)
        selector.forgetElement(first)
        assertNull(selector.current(a))
        selector.update(a, hits)
        selector.clear()
        assertNull(selector.current(a))
    }

    @Test
    fun `invalid ray parameters are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SceneBoundsRaycaster.distance(bounds, transform(), null, zero, zero, 5.0)
        }
        assertFailsWith<IllegalArgumentException> {
            SceneBoundsRaycaster.distance(
                bounds,
                transform(),
                null,
                zero,
                Vec3(1.0, 0.0, 0.0),
                Double.POSITIVE_INFINITY,
            )
        }
        assertFailsWith<IllegalArgumentException> { SceneHoverHit(LocalId("a"), Double.NaN) }
    }

    @Test
    fun `nearer hit beats identifier and changing target leaves then enters`() {
        val selector = SceneHoverSelector()
        val viewer = UUID.randomUUID()
        val a = LocalId("a")
        val b = LocalId("b")
        selector.update(viewer, listOf(SceneHoverHit(a, 3.0)))
        assertEquals(
            listOf(
                SceneHoverTransition(viewer, a, SceneTrigger.HOVER_LEAVE),
                SceneHoverTransition(viewer, b, SceneTrigger.HOVER_ENTER),
            ),
            selector.update(viewer, listOf(SceneHoverHit(a, 3.0), SceneHoverHit(b, 2.0))),
        )
    }
}
