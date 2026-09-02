package gg.grounds.scene.minestom.internal.spatial

import gg.grounds.scene.format.ActivationPolicy
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.SceneRuntimeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import net.minestom.server.coordinate.Vec

class SpatialActivationTest {
    @Test
    fun `floors negative cells and visits only nearby elements`() {
        val index =
            SpatialIndex(
                List(10) {
                    IndexedElement(
                        LocalId("near$it"),
                        ActivationPolicy.AUTOMATIC,
                        Vec3(-0.5, 0.0, -0.5),
                    )
                } +
                    List(1_000) {
                        IndexedElement(
                            LocalId("far$it"),
                            ActivationPolicy.AUTOMATIC,
                            Vec3(10_000.0, 0.0, 10_000.0),
                        )
                    },
                cellEdge = 32.0,
            )

        assertEquals(
            (0 until 10).map { LocalId("near$it") },
            index.candidates(Vec(-0.1, 0.0, -0.1), 1.0),
        )
        assertEquals(10, index.visitedElementCount())
    }

    @Test
    fun `controller evaluation does not visit a distant inactive population`() {
        val controller =
            controller(
                List(10) {
                    IndexedElement(
                        LocalId("near$it"),
                        ActivationPolicy.AUTOMATIC,
                        Vec3(0.0, 0.0, 0.0),
                    )
                } +
                    List(1_000) {
                        IndexedElement(
                            LocalId("far$it"),
                            ActivationPolicy.AUTOMATIC,
                            Vec3(10_000.0, 0.0, 10_000.0),
                        )
                    }
            )

        assertEquals(10, controller.evaluate(listOf(Vec.ZERO)).size)
        assertEquals(10, controller.visitedElementCount())
    }

    @Test
    fun `pending automatic activation is invalidated as soon as eligibility disappears`() {
        val element =
            IndexedElement(LocalId("automatic"), ActivationPolicy.AUTOMATIC, Vec3(0.0, 0.0, 0.0))
        val controller = controller(element)

        assertEquals(
            listOf(ActivationTransition(element.id, ActivationTransitionKind.ACTIVATE, 1L)),
            controller.evaluate(listOf(Vec.ZERO)),
        )
        assertTrue(
            controller.beginActivation(
                ActivationTransition(element.id, ActivationTransitionKind.ACTIVATE, 1L)
            )
        )

        assertEquals(listOf(element.id), controller.reevaluate(emptyList()))
    }

    @Test
    fun `dispatch rejects an activation transition with a stale desire epoch`() {
        val element =
            IndexedElement(LocalId("automatic"), ActivationPolicy.AUTOMATIC, Vec3(0.0, 0.0, 0.0))
        val controller = controller(element)

        val stale = controller.evaluate(listOf(Vec.ZERO)).single()
        controller.reevaluate(emptyList())
        controller.reevaluate(listOf(Vec.ZERO))
        val current = controller.drainTransitions().single()

        assertNotEquals(stale.epoch, current.epoch)
        assertFalse(controller.beginActivation(stale))
        assertTrue(controller.beginActivation(current))
    }

    @Test
    fun `always activates immediately while automatic activates at 64 blocks`() {
        val controller =
            controller(
                IndexedElement(
                    LocalId("always"),
                    ActivationPolicy.ALWAYS,
                    Vec3(10_000.0, 0.0, 0.0),
                ),
                IndexedElement(
                    LocalId("automatic"),
                    ActivationPolicy.AUTOMATIC,
                    Vec3(64.0, 0.0, 0.0),
                ),
            )

        assertEquals(
            listOf(
                ActivationTransition(LocalId("automatic"), ActivationTransitionKind.ACTIVATE, 1L),
                ActivationTransition(LocalId("always"), ActivationTransitionKind.ACTIVATE, 0L),
            ),
            controller.evaluate(listOf(Vec(0.0, 0.0, 0.0))),
        )
    }

    @Test
    fun `keeps active automatic element inside 80 blocks and deactivates after continuous grace`() {
        val clock = TestClock()
        val controller =
            controller(
                IndexedElement(
                    LocalId("automatic"),
                    ActivationPolicy.AUTOMATIC,
                    Vec3(0.0, 0.0, 0.0),
                ),
                clock = clock,
            )
        activate(controller, LocalId("automatic"), Vec.ZERO)

        assertEquals(emptyList(), controller.evaluate(listOf(Vec(80.0, 0.0, 0.0))))
        assertEquals(emptyList(), controller.evaluate(emptyList()))
        clock.advanceMillis(4_999)
        assertEquals(emptyList(), controller.evaluate(emptyList()))
        clock.advanceMillis(1)
        assertEquals(
            listOf(
                ActivationTransition(LocalId("automatic"), ActivationTransitionKind.DEACTIVATE, 2L)
            ),
            controller.evaluate(emptyList()),
        )
    }

    @Test
    fun `re-entry cancels pending automatic deactivation grace`() {
        val clock = TestClock()
        val controller =
            controller(
                IndexedElement(
                    LocalId("automatic"),
                    ActivationPolicy.AUTOMATIC,
                    Vec3(0.0, 0.0, 0.0),
                ),
                clock = clock,
            )
        activate(controller, LocalId("automatic"), Vec.ZERO)

        controller.evaluate(emptyList())
        clock.advanceMillis(4_000)
        assertEquals(emptyList(), controller.evaluate(listOf(Vec(80.0, 0.0, 0.0))))
        clock.advanceMillis(5_000)
        assertEquals(emptyList(), controller.evaluate(emptyList()))
    }

    @Test
    fun `re-entry cancels a queued automatic deactivation before delayed dispatch`() {
        val clock = TestClock()
        val elements =
            List(257) {
                IndexedElement(
                    LocalId("item${it.toString().padStart(3, '0')}"),
                    ActivationPolicy.AUTOMATIC,
                    Vec3(0.0, 0.0, 0.0),
                )
            } + IndexedElement(LocalId("target"), ActivationPolicy.AUTOMATIC, Vec3(320.0, 0.0, 0.0))
        val controller = controller(elements, clock, 256)
        controller.reevaluate(listOf(Vec.ZERO, Vec(320.0, 0.0, 0.0)))
        val transitions = controller.drainTransitions() + controller.drainTransitions()
        transitions.forEach { transition ->
            assertTrue(controller.beginActivation(transition))
            assertTrue(controller.markActive(transition.elementId, transition.epoch))
        }

        assertEquals(emptyList(), controller.evaluate(emptyList()))
        clock.advanceMillis(5_000)
        assertEquals(256, controller.evaluate(emptyList()).size)
        controller.reevaluate(listOf(Vec(320.0, 0.0, 0.0)))

        assertTrue(
            controller.drainTransitions().none {
                it.elementId == LocalId("target") && it.kind == ActivationTransitionKind.DEACTIVATE
            }
        )
    }

    @Test
    fun `orders transitions stably and returns budget leftovers on the next evaluation`() {
        val controller =
            controller(
                List(258) {
                    IndexedElement(
                        LocalId("item${it.toString().padStart(3, '0')}"),
                        ActivationPolicy.ALWAYS,
                        Vec3(0.0, 0.0, 0.0),
                    )
                },
                budget = 256,
            )

        val first = controller.evaluate(emptyList())
        assertEquals(256, first.size)
        assertEquals(LocalId("item000"), first.first().elementId)
        assertEquals(LocalId("item255"), first.last().elementId)
        assertEquals(
            listOf(
                ActivationTransition(LocalId("item256"), ActivationTransitionKind.ACTIVATE, 0L),
                ActivationTransition(LocalId("item257"), ActivationTransitionKind.ACTIVATE, 0L),
            ),
            controller.evaluate(emptyList()),
        )
    }

    private fun activate(controller: ActivationController, elementId: LocalId, position: Vec) {
        controller.reevaluate(listOf(position))
        val transition = controller.drainTransitions().single { it.elementId == elementId }
        assertTrue(controller.beginActivation(transition))
        assertTrue(controller.markActive(elementId, transition.epoch))
    }

    private fun controller(
        vararg elements: IndexedElement,
        clock: TestClock = TestClock(),
        budget: Int = 256,
    ) = controller(elements.asList(), clock, budget)

    private fun controller(
        elements: List<IndexedElement>,
        clock: TestClock = TestClock(),
        budget: Int = 256,
    ) =
        ActivationController(
            SpatialIndex(elements, 32.0),
            SceneRuntimeConfig(transitionBudgetPerTick = budget),
            clock,
        )

    private class TestClock(private var now: Long = 0L) : SceneClock {
        override fun nanoTime(): Long = now

        fun advanceMillis(millis: Long) {
            now += millis * 1_000_000L
        }
    }
}
