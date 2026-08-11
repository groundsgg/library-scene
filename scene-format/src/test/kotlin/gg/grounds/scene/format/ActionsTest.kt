package gg.grounds.scene.format

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ActionsTest {
    @Test
    fun `application arguments are flat and decimal is exact`() {
        val arguments =
            linkedMapOf<LocalId, ApplicationArgument>(
                LocalId("weight") to DecimalArgument(BigDecimal("1.20")),
                LocalId("name") to StringArgument("queue"),
            )
        val action = ApplicationAction(ActionKey("grounds:queue"), arguments)
        arguments.clear()

        assertEquals(
            BigDecimal("1.20"),
            (action.arguments.getValue(LocalId("weight")) as DecimalArgument).value,
        )
        assertEquals(listOf("weight", "name"), action.arguments.keys.map { it.value })
    }

    @Test
    fun `decimal arguments reject canonical plain values longer than numeric token limit`() {
        DecimalArgument(BigDecimal("1e127"))

        assertFailsWith<IllegalArgumentException> { DecimalArgument(BigDecimal("1e200")) }
        assertFailsWith<IllegalArgumentException> { DecimalArgument(BigDecimal("1e1000000")) }
    }

    @Test
    fun `decimal arguments translate canonical scale overflow to the stable limit invariant`() {
        listOf("1000e2147483646", "-1000e2147483646").forEach { token ->
            val failure =
                assertFailsWith<IllegalArgumentException> { DecimalArgument(BigDecimal(token)) }

            assertEquals("Canonical decimal exceeds 128 characters.", failure.message)
            assertIs<ArithmeticException>(failure.cause)
        }
    }
}
