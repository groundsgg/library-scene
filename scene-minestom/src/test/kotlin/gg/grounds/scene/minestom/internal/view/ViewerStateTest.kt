package gg.grounds.scene.minestom.internal.view

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.SceneViewerVisualState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewerStateTest {
    @Test
    fun `stores viewer visual state by player uuid and element id`() {
        val viewers = ViewerStateStore()
        val first =
            ViewerElementKey(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                LocalId("npc"),
            )
        val second =
            ViewerElementKey(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                LocalId("npc"),
            )

        viewers.setScale(first, 1.5)
        viewers.setHighlight(second, true)

        assertEquals(SceneViewerVisualState(1.5, false), viewers.visualState(first))
        assertEquals(SceneViewerVisualState(1.0, true), viewers.visualState(second))
    }
}
