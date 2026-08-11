package gg.grounds.scene.format.internal

import gg.grounds.scene.format.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer

internal object WireMapping {
    fun toDomain(w: SceneWire): SceneDocument {
        if (w.schemaVersion != 1) throw DecodeFailure("/schemaVersion", "UNSUPPORTED_SCHEMA_VERSION", "Schema version must be 1.")
        return SceneDocument(w.schemaVersion, SceneId(w.id), SceneMetadata(w.metadata.name, w.metadata.description, w.metadata.tags.toSet()), SceneCatalogReferences(catalog(w.catalogs.assets), catalog(w.catalogs.actions)), w.groups.map { SceneGroup(LocalId(it.id), it.displayName, it.editorVisible) }, w.elements.map(::element))
    }
    private fun catalog(w: CatalogReferenceWire) = CatalogReference(CatalogId(w.id), w.version)
    private fun element(w: ElementWire): SceneElement = when(w) {
        is PropWire -> Prop(LocalId(w.id), w.group?.let(::LocalId), transform(w.transform), w.visible, activation(w.activation), AssetKey(w.asset), w.initialAnimation?.let(::LocalId))
        is CompositePropWire -> CompositeProp(LocalId(w.id), w.group?.let(::LocalId), transform(w.transform), w.visible, activation(w.activation), w.parts.map { CompositePart(LocalId(it.id), AssetKey(it.asset), transform(it.transform)) })
        is NpcWire -> Npc(LocalId(w.id), w.group?.let(::LocalId), transform(w.transform), w.visible, activation(w.activation), AssetKey(w.body), w.label?.let(::component), vec(w.labelOffset), look(w.look), w.initialAnimation?.let(::LocalId), bounds(w.interactionBounds), w.proximity?.let { ProximitySensor(it.enterRadius,it.exitRadius) }, w.bindings.map(::binding))
    }
    private fun activation(v:String)=enumValueOf<ActivationPolicy>(v)
    private fun transform(w:TransformWire)=Transform(vec(w.position),EulerRotation(w.rotation.yaw,w.rotation.pitch,w.rotation.roll),vec(w.scale))
    private fun vec(w:Vec3Wire)=Vec3(w.x,w.y,w.z)
    private fun bounds(w:BoundsWire)=LocalBounds(vec(w.center),vec(w.size))
    private fun look(w:LookWire):LookBehavior=when(w) { FixedLookWire -> LookBehavior.Fixed; is TrackNearestLookWire -> LookBehavior.TrackNearest(w.maxDistance,w.yawOnly,w.maxTurnDegreesPerSecond) }
    private fun binding(w:BindingWire)=TriggerBinding(enumValueOf<SceneTrigger>(w.trigger),w.conditions.map(::condition),w.cooldownMillis,w.debounceMillis,w.actions.map(::action))
    private fun condition(w:ConditionWire):SceneCondition=when(w) { is HandConditionWire -> HandCondition(enumValueOf<SceneHand>(w.hand)); is SneakingConditionWire -> SneakingCondition(w.sneaking); is PermissionConditionWire -> PermissionCondition(w.permission); is GameModeConditionWire -> GameModeCondition(enumValueOf<SceneGameMode>(w.gameMode)) }
    private fun action(w:ActionWire):SceneAction=when(w) {
        is StartAnimationWire -> StartAnimationAction(target(w.target),LocalId(w.animation)); is StopAnimationWire -> StopAnimationAction(target(w.target),w.animation?.let(::LocalId)); is PlaySoundWire -> PlaySoundAction(AssetKey(w.sound),w.volume,w.pitch); is SetViewerScaleWire -> SetViewerScaleAction(target(w.target),w.multiplier,w.transitionMillis); is SetViewerHighlightWire -> SetViewerHighlightAction(target(w.target),w.enabled,w.transitionMillis); is SendMessageWire -> SendMessageAction(component(w.message)); is SendActionBarWire -> SendActionBarAction(component(w.message)); is ShowTitleWire -> ShowTitleAction(component(w.title),component(w.subtitle),w.fadeInMillis,w.stayMillis,w.fadeOutMillis); is EmitParticleWire -> EmitParticleAction(target(w.target),AssetKey(w.particle),w.count,vec(w.offset),w.speed); is ApplicationWire -> ApplicationAction(ActionKey(w.key),w.arguments.mapKeys { LocalId(it.key) }.mapValues { argument(it.value) })
    }
    private fun target(w:TargetWire)=ElementTarget(LocalId(w.element),w.part?.let(::LocalId))
    private fun argument(w:ArgumentWire):ApplicationArgument=when(w) { is StringArgumentWire -> StringArgument(w.value); is LongArgumentWire -> LongArgument(w.value); is DecimalArgumentWire -> DecimalArgument(w.value); is BooleanArgumentWire -> BooleanArgument(w.value); is EnumArgumentWire -> EnumArgument(LocalId(w.value)); is AssetArgumentWire -> AssetArgument(AssetKey(w.value)) }
    private fun component(json:String):Component = GsonComponentSerializer.gson().deserialize(json)
}
