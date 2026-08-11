package gg.grounds.scene.format.internal

import java.math.BigDecimal
import tools.jackson.databind.JsonNode

/** Internal, explicit JSON representation. Its discriminator strings are part of Scene v1. */
internal data class SceneWire(
    val schemaVersion: Int,
    val id: String,
    val metadata: MetadataWire,
    val catalogs: CatalogsWire,
    val groups: List<GroupWire>,
    val elements: List<ElementWire>,
)

internal data class MetadataWire(val name: String, val description: String?, val tags: List<String>)

internal data class CatalogsWire(
    val assets: CatalogReferenceWire,
    val actions: CatalogReferenceWire,
)

internal data class CatalogReferenceWire(val id: String, val version: String)

internal data class GroupWire(val id: String, val displayName: String, val editorVisible: Boolean)

internal data class Vec3Wire(val x: Double, val y: Double, val z: Double)

internal data class RotationWire(val yaw: Double, val pitch: Double, val roll: Double)

internal data class TransformWire(
    val position: Vec3Wire,
    val rotation: RotationWire,
    val scale: Vec3Wire,
)

internal data class BoundsWire(val center: Vec3Wire, val size: Vec3Wire)

internal sealed interface ElementWire {
    val type: String
    val id: String
    val group: String?
    val transform: TransformWire
    val visible: Boolean
    val activation: String
}

internal data class PropWire(
    override val id: String,
    override val group: String?,
    override val transform: TransformWire,
    override val visible: Boolean,
    override val activation: String,
    val asset: String,
    val initialAnimation: String?,
) : ElementWire {
    override val type = "prop"
}

internal data class CompositePartWire(
    val id: String,
    val asset: String,
    val transform: TransformWire,
)

internal data class CompositePropWire(
    override val id: String,
    override val group: String?,
    override val transform: TransformWire,
    override val visible: Boolean,
    override val activation: String,
    val parts: List<CompositePartWire>,
) : ElementWire {
    override val type = "composite_prop"
}

internal data class NpcWire(
    override val id: String,
    override val group: String?,
    override val transform: TransformWire,
    override val visible: Boolean,
    override val activation: String,
    val body: String,
    val label: JsonNode?,
    val labelOffset: Vec3Wire,
    val look: LookWire,
    val initialAnimation: String?,
    val interactionBounds: BoundsWire,
    val proximity: ProximityWire?,
    val bindings: List<BindingWire>,
) : ElementWire {
    override val type = "npc"
}

internal sealed interface LookWire {
    val type: String
}

internal data object FixedLookWire : LookWire {
    override val type = "fixed"
}

internal data class TrackNearestLookWire(
    val maxDistance: Double,
    val yawOnly: Boolean,
    val maxTurnDegreesPerSecond: Double,
) : LookWire {
    override val type = "track_nearest"
}

internal data class ProximityWire(val enterRadius: Double, val exitRadius: Double)

internal data class BindingWire(
    val trigger: String,
    val conditions: List<ConditionWire>,
    val cooldownMillis: Long,
    val debounceMillis: Long,
    val actions: List<ActionWire>,
)

internal sealed interface ConditionWire {
    val type: String
}

internal data class HandConditionWire(val hand: String) : ConditionWire {
    override val type = "hand"
}

internal data class SneakingConditionWire(val sneaking: Boolean) : ConditionWire {
    override val type = "sneaking"
}

internal data class PermissionConditionWire(val permission: String) : ConditionWire {
    override val type = "permission"
}

internal data class GameModeConditionWire(val gameMode: String) : ConditionWire {
    override val type = "game_mode"
}

internal sealed interface ActionWire {
    val type: String
}

internal data class StartAnimationWire(val target: TargetWire, val animation: String) : ActionWire {
    override val type = "start_animation"
}

internal data class StopAnimationWire(val target: TargetWire, val animation: String?) : ActionWire {
    override val type = "stop_animation"
}

internal data class PlaySoundWire(val sound: String, val volume: Double, val pitch: Double) :
    ActionWire {
    override val type = "play_sound"
}

internal data class SetViewerScaleWire(
    val target: TargetWire,
    val multiplier: Double,
    val transitionMillis: Long,
) : ActionWire {
    override val type = "set_viewer_scale"
}

internal data class SetViewerHighlightWire(
    val target: TargetWire,
    val enabled: Boolean,
    val transitionMillis: Long,
) : ActionWire {
    override val type = "set_viewer_highlight"
}

internal data class SendMessageWire(val message: JsonNode) : ActionWire {
    override val type = "send_message"
}

internal data class SendActionBarWire(val message: JsonNode) : ActionWire {
    override val type = "send_action_bar"
}

internal data class ShowTitleWire(
    val title: JsonNode,
    val subtitle: JsonNode,
    val fadeInMillis: Long,
    val stayMillis: Long,
    val fadeOutMillis: Long,
) : ActionWire {
    override val type = "show_title"
}

internal data class EmitParticleWire(
    val target: TargetWire,
    val particle: String,
    val count: Int,
    val offset: Vec3Wire,
    val speed: Double,
) : ActionWire {
    override val type = "emit_particle"
}

internal data class ApplicationWire(val key: String, val arguments: Map<String, ArgumentWire>) :
    ActionWire {
    override val type = "application"
}

internal data class TargetWire(val element: String, val part: String?)

internal sealed interface ArgumentWire {
    val type: String
}

internal data class StringArgumentWire(val value: String) : ArgumentWire {
    override val type = "string"
}

internal data class LongArgumentWire(val value: Long) : ArgumentWire {
    override val type = "long"
}

internal data class DecimalArgumentWire(val value: BigDecimal) : ArgumentWire {
    override val type = "decimal"
}

internal data class BooleanArgumentWire(val value: Boolean) : ArgumentWire {
    override val type = "boolean"
}

internal data class EnumArgumentWire(val value: String) : ArgumentWire {
    override val type = "enum"
}

internal data class AssetArgumentWire(val value: String) : ArgumentWire {
    override val type = "asset"
}
