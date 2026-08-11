package gg.grounds.scene.format

import gg.grounds.scene.format.SceneProblemCode.INVALID_SCALE
import kotlin.test.Test
import kotlin.test.assertEquals

class ProblemsTest {
    @Test
    fun `problems have complete stable ordering`() {
        val actual = listOf(
            SceneProblem("/elements/1", INVALID_SCALE, "grounds:lobby#b", "bad"),
            SceneProblem("/elements/0", INVALID_SCALE, "grounds:lobby#a", "bad"),
        ).sortedWith(SceneProblem.ORDERING)
        assertEquals(listOf("/elements/0", "/elements/1"), actual.map(SceneProblem::path))
    }

    @Test
    fun `problems sort by code then message using Unicode code points`() {
        val actual = listOf(
            SceneProblem("/same", SceneProblemCode.UNKNOWN_ACTION, null, "z"),
            SceneProblem("/same", SceneProblemCode.INVALID_SCALE, null, "z"),
            SceneProblem("/same", SceneProblemCode.INVALID_SCALE, null, "a"),
            SceneProblem("/\uE000", INVALID_SCALE, null, "z"),
            SceneProblem("/\uD83D\uDE00", INVALID_SCALE, null, "z"),
        ).sortedWith(SceneProblem.ORDERING)
        assertEquals(listOf("/same", "/same", "/same", "/\uE000", "/\uD83D\uDE00"), actual.map(SceneProblem::path))
        assertEquals(listOf("a", "z", "z", "z", "z"), actual.map(SceneProblem::message))
    }
}
