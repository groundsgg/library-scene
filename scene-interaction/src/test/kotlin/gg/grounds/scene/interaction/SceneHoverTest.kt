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
