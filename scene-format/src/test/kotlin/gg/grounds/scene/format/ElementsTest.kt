package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals

class ElementsTest {
    @Test
    fun `composite parts retain authored order through an immutable snapshot`() {
        val parts = mutableListOf(
            CompositePart(LocalId("lamp"), AssetKey("grounds:lamp"), transform()),
            CompositePart(LocalId("shade"), AssetKey("grounds:shade"), transform()),
        )

        val prop = CompositeProp(LocalId("light"), null, transform(), parts = parts)
        parts.clear()

        assertEquals(listOf("lamp", "shade"), prop.parts.map { it.id.value })
    }

    private fun transform() = Transform(ORIGIN, ZERO_ROTATION, Vec3(1.0, 1.0, 1.0))
}
