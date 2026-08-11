package gg.grounds.scene.format

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
