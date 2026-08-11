package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InteractionTest {
    @Test
    fun `proximity uses hysteresis`() {
        assertFailsWith<IllegalArgumentException> { ProximitySensor(5.0, 5.0) }
        assertFailsWith<IllegalArgumentException> { ProximitySensor(Double.NaN, 6.0) }
        assertEquals(ProximitySensor(5.0, 6.0), ProximitySensor(5.0, 6.0))
    }

    @Test
    fun `binding preserves action order and rejects an empty chain`() {
        assertFailsWith<IllegalArgumentException> {
            TriggerBinding(SceneTrigger.RIGHT_CLICK, emptyList(), 0, 0, emptyList())
        }

        val actions = mutableListOf<SceneAction>(message("first"), message("second"))
        val binding = TriggerBinding(SceneTrigger.LEFT_CLICK, emptyList(), 0, 0, actions)
        actions.clear()

        assertEquals(
            listOf(message("first").message, message("second").message),
            binding.actions.map { (it as SendMessageAction).message },
        )
    }

    @Test
    fun `interaction numeric values reject invalid bounds`() {
        assertFailsWith<IllegalArgumentException> { LookBehavior.TrackNearest(0.0, true, 90.0) }
        assertFailsWith<IllegalArgumentException> {
            PlaySoundAction(AssetKey("grounds:bell"), 0.0, 1.0)
        }
        assertFailsWith<IllegalArgumentException> { SetViewerScaleAction(target(), 0.0, 0) }
        assertFailsWith<IllegalArgumentException> { SetViewerHighlightAction(target(), true, -1) }
        assertFailsWith<IllegalArgumentException> {
            EmitParticleAction(target(), AssetKey("grounds:spark"), -1, ORIGIN, 0.0)
        }
        assertFailsWith<IllegalArgumentException> { PermissionCondition("permission node") }
    }

    private fun target() = ElementTarget(LocalId("npc"), null)

    private fun message(value: String) =
        SendMessageAction(net.kyori.adventure.text.Component.text(value))
}
