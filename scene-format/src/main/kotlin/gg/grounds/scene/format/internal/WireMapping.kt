package gg.grounds.scene.format.internal

import gg.grounds.scene.format.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal object WireMapping {
    private val componentMapper = JsonMapper.builder().build()

    fun toCanonicalWire(scene: SceneDocument): SceneWire =
        SceneWire(
            scene.schemaVersion,
            scene.id.value,
            MetadataWire(
                scene.metadata.name,
                scene.metadata.description,
                scene.metadata.tags.sortedWith(CODE_POINT_ORDER),
            ),
            CatalogsWire(
                CatalogReferenceWire(scene.catalogs.assets.id.value, scene.catalogs.assets.version),
                CatalogReferenceWire(
                    scene.catalogs.actions.id.value,
                    scene.catalogs.actions.version,
                ),
            ),
            scene.groups.sortedWith(compareBy(CODE_POINT_ORDER) { it.id.value }).map {
                GroupWire(it.id.value, it.displayName, it.editorVisible)
            },
            scene.elements
                .sortedWith(compareBy(CODE_POINT_ORDER) { it.id.value })
                .map(::canonicalElement),
        )

    private fun canonicalElement(element: SceneElement): ElementWire =
        when (element) {
            is Prop ->
                PropWire(
                    element.id.value,
                    element.group?.value,
                    transform(element.transform),
                    element.visible,
                    element.activation.name,
                    element.asset.value,
                    element.initialAnimation?.value,
                )
            is CompositeProp ->
                CompositePropWire(
                    element.id.value,
                    element.group?.value,
                    transform(element.transform),
                    element.visible,
                    element.activation.name,
                    element.parts.sortedWith(compareBy(CODE_POINT_ORDER) { it.id.value }).map {
                        CompositePartWire(it.id.value, it.asset.value, transform(it.transform))
                    },
                )
            is Npc ->
                NpcWire(
                    element.id.value,
                    element.group?.value,
                    transform(element.transform),
                    element.visible,
                    element.activation.name,
                    element.body.value,
                    element.label?.let(::component),
                    vec(element.labelOffset),
                    look(element.look),
                    element.initialAnimation?.value,
                    bounds(element.interactionBounds),
                    element.proximity?.let { ProximityWire(it.enterRadius, it.exitRadius) },
                    element.bindings.map(::binding),
                )
        }

    private fun transform(value: Transform) =
        TransformWire(
            vec(value.position),
            RotationWire(value.rotation.yaw, value.rotation.pitch, value.rotation.roll),
            vec(value.scale),
        )

    private fun vec(value: Vec3) = Vec3Wire(value.x, value.y, value.z)

    private fun bounds(value: LocalBounds) = BoundsWire(vec(value.center), vec(value.size))

    private fun look(value: LookBehavior): LookWire =
        when (value) {
            LookBehavior.Fixed -> FixedLookWire
            is LookBehavior.TrackNearest ->
                TrackNearestLookWire(
                    value.maxDistance,
                    value.yawOnly,
                    value.maxTurnDegreesPerSecond,
                )
        }

    private fun binding(value: TriggerBinding) =
        BindingWire(
            value.trigger.name,
            value.conditions.map(::condition).sortedBy(CanonicalJson::compact),
            value.cooldownMillis,
            value.debounceMillis,
            value.actions.map(::action),
        )

    private fun condition(value: SceneCondition): ConditionWire =
        when (value) {
            is HandCondition -> HandConditionWire(value.hand.name)
            is SneakingCondition -> SneakingConditionWire(value.sneaking)
            is PermissionCondition -> PermissionConditionWire(value.permission)
            is GameModeCondition -> GameModeConditionWire(value.gameMode.name)
        }

    private fun action(value: SceneAction): ActionWire =
        when (value) {
            is StartAnimationAction ->
                StartAnimationWire(target(value.target), value.animation.value)
            is StopAnimationAction ->
                StopAnimationWire(target(value.target), value.animation?.value)
            is PlaySoundAction -> PlaySoundWire(value.sound.value, value.volume, value.pitch)
            is SetViewerScaleAction ->
                SetViewerScaleWire(target(value.target), value.multiplier, value.transitionMillis)
            is SetViewerHighlightAction ->
                SetViewerHighlightWire(target(value.target), value.enabled, value.transitionMillis)
            is SendMessageAction -> SendMessageWire(component(value.message))
            is SendActionBarAction -> SendActionBarWire(component(value.message))
            is ShowTitleAction ->
                ShowTitleWire(
                    component(value.title),
                    component(value.subtitle),
                    value.fadeInMillis,
                    value.stayMillis,
                    value.fadeOutMillis,
                )
            is EmitParticleAction ->
                EmitParticleWire(
                    target(value.target),
                    value.particle.value,
                    value.count,
                    vec(value.offset),
                    value.speed,
                )
            is ApplicationAction ->
                ApplicationWire(
                    value.key.value,
                    value.arguments.entries
                        .sortedWith(compareBy(CODE_POINT_ORDER) { it.key.value })
                        .associate { it.key.value to argument(it.value) },
                )
        }

    private fun target(value: ElementTarget) = TargetWire(value.element.value, value.part?.value)

    private fun argument(value: ApplicationArgument): ArgumentWire =
        when (value) {
            is StringArgument -> StringArgumentWire(value.value)
            is LongArgument -> LongArgumentWire(value.value)
            is DecimalArgument -> DecimalArgumentWire(value.value)
            is BooleanArgument -> BooleanArgumentWire(value.value)
            is EnumArgument -> EnumArgumentWire(value.value.value)
            is AssetArgument -> AssetArgumentWire(value.value.value)
        }

    private fun component(value: Component): JsonNode =
        CanonicalJson.canonicalize(
            componentMapper.readTree(GsonComponentSerializer.gson().serialize(value))
        )

    fun toDomain(w: SceneWire): SceneDocument {
        if (w.schemaVersion != 1)
            throw DecodeFailure(
                "/schemaVersion",
                "UNSUPPORTED_SCHEMA_VERSION",
                "Schema version must be 1.",
            )
        return SceneDocument(
            w.schemaVersion,
            SceneId(w.id),
            SceneMetadata(w.metadata.name, w.metadata.description, w.metadata.tags.toSet()),
            SceneCatalogReferences(catalog(w.catalogs.assets), catalog(w.catalogs.actions)),
            w.groups.map { SceneGroup(LocalId(it.id), it.displayName, it.editorVisible) },
            w.elements.map(::element),
        )
    }

    private fun catalog(w: CatalogReferenceWire) = CatalogReference(CatalogId(w.id), w.version)

    private fun element(w: ElementWire): SceneElement =
        when (w) {
            is PropWire ->
                Prop(
                    LocalId(w.id),
                    w.group?.let(::LocalId),
                    transform(w.transform),
                    w.visible,
                    activation(w.activation),
                    AssetKey(w.asset),
                    w.initialAnimation?.let(::LocalId),
                )
            is CompositePropWire ->
                CompositeProp(
                    LocalId(w.id),
                    w.group?.let(::LocalId),
                    transform(w.transform),
                    w.visible,
                    activation(w.activation),
                    w.parts.map {
                        CompositePart(LocalId(it.id), AssetKey(it.asset), transform(it.transform))
                    },
                )
            is NpcWire ->
                Npc(
                    LocalId(w.id),
                    w.group?.let(::LocalId),
                    transform(w.transform),
                    w.visible,
                    activation(w.activation),
                    AssetKey(w.body),
                    w.label?.let(::component),
                    vec(w.labelOffset),
                    look(w.look),
                    w.initialAnimation?.let(::LocalId),
                    bounds(w.interactionBounds),
                    w.proximity?.let { ProximitySensor(it.enterRadius, it.exitRadius) },
                    w.bindings.map(::binding),
                )
        }

    private fun activation(v: String) =
        when (v) {
            "AUTOMATIC" -> ActivationPolicy.AUTOMATIC
            "ALWAYS" -> ActivationPolicy.ALWAYS
            else -> error("WireReader accepted an invalid activation policy")
        }

    private fun transform(w: TransformWire) =
        Transform(
            vec(w.position),
            EulerRotation(w.rotation.yaw, w.rotation.pitch, w.rotation.roll),
            vec(w.scale),
        )

    private fun vec(w: Vec3Wire) = Vec3(w.x, w.y, w.z)

    private fun bounds(w: BoundsWire) = LocalBounds(vec(w.center), vec(w.size))

    private fun look(w: LookWire): LookBehavior =
        when (w) {
            FixedLookWire -> LookBehavior.Fixed
            is TrackNearestLookWire ->
                LookBehavior.TrackNearest(w.maxDistance, w.yawOnly, w.maxTurnDegreesPerSecond)
        }

    private fun binding(w: BindingWire) =
        TriggerBinding(
            trigger(w.trigger),
            w.conditions.map(::condition),
            w.cooldownMillis,
            w.debounceMillis,
            w.actions.map(::action),
        )

    private fun trigger(v: String) =
        when (v) {
            "LEFT_CLICK" -> SceneTrigger.LEFT_CLICK
            "RIGHT_CLICK" -> SceneTrigger.RIGHT_CLICK
            "HOVER_ENTER" -> SceneTrigger.HOVER_ENTER
            "HOVER_LEAVE" -> SceneTrigger.HOVER_LEAVE
            "PROXIMITY_ENTER" -> SceneTrigger.PROXIMITY_ENTER
            "PROXIMITY_LEAVE" -> SceneTrigger.PROXIMITY_LEAVE
            else -> error("WireReader accepted an invalid trigger")
        }

    private fun condition(w: ConditionWire): SceneCondition =
        when (w) {
            is HandConditionWire ->
                HandCondition(if (w.hand == "MAIN") SceneHand.MAIN else SceneHand.OFF)
            is SneakingConditionWire -> SneakingCondition(w.sneaking)
            is PermissionConditionWire -> PermissionCondition(w.permission)
            is GameModeConditionWire ->
                GameModeCondition(
                    when (w.gameMode) {
                        "SURVIVAL" -> SceneGameMode.SURVIVAL
                        "CREATIVE" -> SceneGameMode.CREATIVE
                        "ADVENTURE" -> SceneGameMode.ADVENTURE
                        "SPECTATOR" -> SceneGameMode.SPECTATOR
                        else -> error("WireReader accepted an invalid game mode")
                    }
                )
        }

    private fun action(w: ActionWire): SceneAction =
        when (w) {
            is StartAnimationWire -> StartAnimationAction(target(w.target), LocalId(w.animation))
            is StopAnimationWire ->
                StopAnimationAction(target(w.target), w.animation?.let(::LocalId))
            is PlaySoundWire -> PlaySoundAction(AssetKey(w.sound), w.volume, w.pitch)
            is SetViewerScaleWire ->
                SetViewerScaleAction(target(w.target), w.multiplier, w.transitionMillis)
            is SetViewerHighlightWire ->
                SetViewerHighlightAction(target(w.target), w.enabled, w.transitionMillis)
            is SendMessageWire -> SendMessageAction(component(w.message))
            is SendActionBarWire -> SendActionBarAction(component(w.message))
            is ShowTitleWire ->
                ShowTitleAction(
                    component(w.title),
                    component(w.subtitle),
                    w.fadeInMillis,
                    w.stayMillis,
                    w.fadeOutMillis,
                )
            is EmitParticleWire ->
                EmitParticleAction(
                    target(w.target),
                    AssetKey(w.particle),
                    w.count,
                    vec(w.offset),
                    w.speed,
                )
            is ApplicationWire ->
                ApplicationAction(
                    ActionKey(w.key),
                    w.arguments.mapKeys { LocalId(it.key) }.mapValues { argument(it.value) },
                )
        }

    private fun target(w: TargetWire) = ElementTarget(LocalId(w.element), w.part?.let(::LocalId))

    private fun argument(w: ArgumentWire): ApplicationArgument =
        when (w) {
            is StringArgumentWire -> StringArgument(w.value)
            is LongArgumentWire -> LongArgument(w.value)
            is DecimalArgumentWire -> DecimalArgument(w.value)
            is BooleanArgumentWire -> BooleanArgument(w.value)
            is EnumArgumentWire -> EnumArgument(LocalId(w.value))
            is AssetArgumentWire -> AssetArgument(AssetKey(w.value))
        }

    private fun component(json: JsonNode): Component =
        GsonComponentSerializer.gson().deserialize(json.toString())
}

internal val CODE_POINT_ORDER: Comparator<String> = Comparator { left, right ->
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val comparison = left.codePointAt(leftIndex).compareTo(right.codePointAt(rightIndex))
        if (comparison != 0) return@Comparator comparison
        leftIndex += Character.charCount(left.codePointAt(leftIndex))
        rightIndex += Character.charCount(right.codePointAt(rightIndex))
    }
    (left.length - leftIndex).compareTo(right.length - rightIndex)
}
