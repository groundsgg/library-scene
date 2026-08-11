package gg.grounds.scene.format

import net.kyori.adventure.text.Component

enum class ActivationPolicy {
    AUTOMATIC,
    ALWAYS,
}

sealed interface SceneElement {
    val id: LocalId
    val group: LocalId?
    val transform: Transform
    val visible: Boolean
    val activation: ActivationPolicy
}

data class Prop(
    override val id: LocalId,
    override val group: LocalId?,
    override val transform: Transform,
    override val visible: Boolean = true,
    override val activation: ActivationPolicy = ActivationPolicy.AUTOMATIC,
    val asset: AssetKey,
    val initialAnimation: LocalId?,
) : SceneElement

data class CompositePart(val id: LocalId, val asset: AssetKey, val transform: Transform)

@ConsistentCopyVisibility
data class CompositeProp
private constructor(
    override val id: LocalId,
    override val group: LocalId?,
    override val transform: Transform,
    override val visible: Boolean,
    override val activation: ActivationPolicy,
    val parts: List<CompositePart>,
    @Suppress("unused") private val canonical: Unit,
) : SceneElement {
    constructor(
        id: LocalId,
        group: LocalId?,
        transform: Transform,
        visible: Boolean = true,
        activation: ActivationPolicy = ActivationPolicy.AUTOMATIC,
        parts: List<CompositePart>,
    ) : this(id, group, transform, visible, activation, immutableListCopy(parts), Unit)
}

@ConsistentCopyVisibility
data class Npc
private constructor(
    override val id: LocalId,
    override val group: LocalId?,
    override val transform: Transform,
    override val visible: Boolean,
    override val activation: ActivationPolicy,
    val body: AssetKey,
    val label: Component?,
    val labelOffset: Vec3,
    val look: LookBehavior,
    val initialAnimation: LocalId?,
    val interactionBounds: LocalBounds,
    val proximity: ProximitySensor?,
    val bindings: List<TriggerBinding>,
    @Suppress("unused") private val canonical: Unit,
) : SceneElement {
    constructor(
        id: LocalId,
        group: LocalId?,
        transform: Transform,
        visible: Boolean = true,
        activation: ActivationPolicy = ActivationPolicy.AUTOMATIC,
        body: AssetKey,
        label: Component?,
        labelOffset: Vec3,
        look: LookBehavior,
        initialAnimation: LocalId?,
        interactionBounds: LocalBounds,
        proximity: ProximitySensor?,
        bindings: List<TriggerBinding>,
    ) : this(
        id,
        group,
        transform,
        visible,
        activation,
        body,
        label,
        labelOffset,
        look,
        initialAnimation,
        interactionBounds,
        proximity,
        immutableListCopy(bindings),
        Unit,
    )
}
