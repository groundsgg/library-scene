package gg.grounds.scene.format

import gg.grounds.scene.format.SceneProblemCode.FORBIDDEN_TEXT_EVENT
import gg.grounds.scene.format.internal.ComponentSafety
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent

class ComponentSafetyTest {
    @Test
    fun `nested child translatable argument and separator events are rejected at stable paths`() {
        val component = Component.text("root").append(
            Component.text("child").clickEvent(ClickEvent.runCommand("/op @s")).insertion("payload"),
        ).append(
            Component.translatable(
                "grounds.greeting",
                Component.text("argument").hoverEvent(HoverEvent.showText(Component.text("hover"))),
            ),
        ).append(
            Component.selector()
                .pattern("@a")
                .separator(Component.text(",").insertion("separator"))
                .build(),
        )

        val problems = ComponentSafety.findProblems(component, "/elements/0/label", "grounds:lobby#npc")

        assertEquals(
            listOf(
                "/elements/0/label/components/1/clickEvent",
                "/elements/0/label/components/1/insertion",
                "/elements/0/label/components/3/hoverEvent",
                "/elements/0/label/components/6/insertion",
            ),
            problems.map(SceneProblem::path),
        )
        assertEquals(List(4) { FORBIDDEN_TEXT_EVENT }, problems.map(SceneProblem::code))
        assertEquals(List(4) { "grounds:lobby#npc" }, problems.map(SceneProblem::qualifiedIdentity))
    }

    @Test
    fun `text translation colour decoration font and children are accepted`() {
        val component = Component.text("root")
            .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
            .font(net.kyori.adventure.key.Key.key("grounds:display"))
            .append(Component.translatable("grounds.greeting", Component.text("friend")))

        assertTrue(ComponentSafety.findProblems(component, "/label", null).isEmpty())
    }
}
