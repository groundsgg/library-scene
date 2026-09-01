package gg.grounds.scene.minestom.internal.spatial

import gg.grounds.scene.format.ActivationPolicy
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.SceneRuntimeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Vec

class SpatialActivationTest {
    @Test
    fun `floors negative cells and visits only nearby elements`() {
        val index = SpatialIndex(
            List(10) { IndexedElement(LocalId("near$it"), ActivationPolicy.AUTOMATIC, Vec3(-0.5, 0.0, -0.5)) } +
                List(1_000) { IndexedElement(LocalId("far$it"), ActivationPolicy.AUTOMATIC, Vec3(10_000.0, 0.0, 10_000.0)) },
            cellEdge = 32.0,
        )

        assertEquals((0 until 10).map { LocalId("near$it") }, index.candidates(Vec(-0.1, 0.0, -0.1), 1.0))
        assertEquals(10, index.visitedElementCount())
    }

    @Test
    fun `always activates immediately while automatic activates at 64 blocks`() {
        val controller = controller(
            IndexedElement(LocalId("always"), ActivationPolicy.ALWAYS, Vec3(10_000.0, 0.0, 0.0)),
            IndexedElement(LocalId("automatic"), ActivationPolicy.AUTOMATIC, Vec3(64.0, 0.0, 0.0)),
        )

        assertEquals(
            listOf(
                ActivationTransition(LocalId("automatic"), ActivationTransitionKind.ACTIVATE),
                ActivationTransition(LocalId("always"), ActivationTransitionKind.ACTIVATE),
            ),
            controller.evaluate(listOf(Vec(0.0, 0.0, 0.0))),
        )
    }

    @Test
    fun `keeps active automatic element inside 80 blocks and deactivates after continuous grace`() {
        val clock = TestClock()
        val controller = controller(IndexedElement(LocalId("automatic"), ActivationPolicy.AUTOMATIC, Vec3(0.0, 0.0, 0.0)), clock = clock)
        controller.markActive(LocalId("automatic"))

        assertEquals(emptyList(), controller.evaluate(listOf(Vec(80.0, 0.0, 0.0))))
        assertEquals(emptyList(), controller.evaluate(emptyList()))
        clock.advanceMillis(4_999)
        assertEquals(emptyList(), controller.evaluate(emptyList()))
        clock.advanceMillis(1)
        assertEquals(listOf(ActivationTransition(LocalId("automatic"), ActivationTransitionKind.DEACTIVATE)), controller.evaluate(emptyList()))
    }

    @Test
    fun `re-entry cancels pending automatic deactivation grace`() {
        val clock = TestClock()
        val controller = controller(IndexedElement(LocalId("automatic"), ActivationPolicy.AUTOMATIC, Vec3(0.0, 0.0, 0.0)), clock = clock)
        controller.markActive(LocalId("automatic"))

        controller.evaluate(emptyList())
        clock.advanceMillis(4_000)
        assertEquals(emptyList(), controller.evaluate(listOf(Vec(80.0, 0.0, 0.0))))
        clock.advanceMillis(5_000)
        assertEquals(emptyList(), controller.evaluate(emptyList()))
    }

    @Test
    fun `orders transitions stably and returns budget leftovers on the next evaluation`() {
        val controller = controller(
            List(258) { IndexedElement(LocalId("item${it.toString().padStart(3, '0')}"), ActivationPolicy.ALWAYS, Vec3(0.0, 0.0, 0.0)) },
            budget = 256,
        )

        val first = controller.evaluate(emptyList())
        assertEquals(256, first.size)
        assertEquals(LocalId("item000"), first.first().elementId)
        assertEquals(LocalId("item255"), first.last().elementId)
        assertEquals(
            listOf(
                ActivationTransition(LocalId("item256"), ActivationTransitionKind.ACTIVATE),
                ActivationTransition(LocalId("item257"), ActivationTransitionKind.ACTIVATE),
            ),
            controller.evaluate(emptyList()),
        )
    }

    private fun controller(vararg elements: IndexedElement, clock: TestClock = TestClock(), budget: Int = 256) =
        controller(elements.asList(), clock, budget)

    private fun controller(elements: List<IndexedElement>, clock: TestClock = TestClock(), budget: Int = 256) =
        ActivationController(
            SpatialIndex(elements, 32.0),
            SceneRuntimeConfig(transitionBudgetPerTick = budget),
            clock,
        )

    private class TestClock(private var now: Long = 0L) : SceneClock {
        override fun nanoTime(): Long = now
        fun advanceMillis(millis: Long) { now += millis * 1_000_000L }
    }
}
