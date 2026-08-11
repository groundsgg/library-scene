package gg.grounds.scene.format.internal

import gg.grounds.scene.format.SceneProblem
import gg.grounds.scene.format.SceneProblemCode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorFlag
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.NBTComponent
import net.kyori.adventure.text.SelectorComponent

internal object ComponentSafety {
    private val iteratorFlags =
        setOf(
            ComponentIteratorFlag.INCLUDE_HOVER_SHOW_ENTITY_NAME,
            ComponentIteratorFlag.INCLUDE_HOVER_SHOW_TEXT_COMPONENT,
            ComponentIteratorFlag.INCLUDE_TRANSLATABLE_COMPONENT_ARGUMENTS,
        )

    fun findProblems(component: Component, path: String, identity: String?): List<SceneProblem> {
        val components =
            component.iterable(ComponentIteratorType.DEPTH_FIRST, iteratorFlags).toMutableList()
        var index = 0
        while (index < components.size) {
            separator(components[index])?.let { separator ->
                components += separator.iterable(ComponentIteratorType.DEPTH_FIRST, iteratorFlags)
            }
            index++
        }

        return buildList {
                components.forEachIndexed { index, node ->
                    if (node.clickEvent() != null)
                        add(forbidden("$path/components/$index/clickEvent", identity))
                    if (node.hoverEvent() != null)
                        add(forbidden("$path/components/$index/hoverEvent", identity))
                    if (node.insertion() != null)
                        add(forbidden("$path/components/$index/insertion", identity))
                }
            }
            .sortedWith(SceneProblem.ORDERING)
    }

    private fun separator(component: Component): Component? =
        when (component) {
            is SelectorComponent -> component.separator()
            is NBTComponent<*, *> -> component.separator()
            else -> null
        }

    private fun forbidden(path: String, identity: String?) =
        SceneProblem(
            path,
            SceneProblemCode.FORBIDDEN_TEXT_EVENT,
            identity,
            "Interactive text events are not allowed.",
        )
}
