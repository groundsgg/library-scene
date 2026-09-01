package gg.grounds.scene.minestom.internal.action

import gg.grounds.scene.format.CompositeProp
import gg.grounds.scene.format.ElementTarget
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import gg.grounds.scene.minestom.internal.runtime.LogicalElementState

internal data class ResolvedActionTarget(
    val state: LogicalElementState,
    val partId: LocalId?,
    val transform: SceneRenderTransform,
    val activeHandles: List<gg.grounds.scene.minestom.RenderedAssetHandle>,
)

internal class ActionTargetResolver(
    private val elements: Map<LocalId, LogicalElementState>,
    private val activeElements: Map<LocalId, ActiveElement>,
) {
    fun resolve(target: ElementTarget): ResolvedActionTarget? {
        val state = elements[target.element] ?: return null
        val element = state.element
        val part = target.part
        val authoredTransform =
            when {
                part == null -> SceneRenderTransform(element.transform, null)
                element !is CompositeProp -> return null
                else ->
                    element.parts
                        .firstOrNull { it.id == part }
                        ?.let { SceneRenderTransform(element.transform, it.transform) }
                        ?: return null
            }
        val active = activeElements[target.element]?.takeIf { it.generation == state.generation }
        val currentRoot =
            active?.transformOr(SceneRenderTransform(element.transform, null))?.root
                ?: element.transform
        val transform = SceneRenderTransform(currentRoot, authoredTransform.local)
        val handles =
            when {
                active == null -> emptyList()
                else -> active.handlesFor(part)
            }
        return ResolvedActionTarget(state, part, transform, handles)
    }
}
