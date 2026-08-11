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
            sceneId(w.id, "/id"),
            SceneMetadata(w.metadata.name, w.metadata.description, w.metadata.tags.toSet()),
            SceneCatalogReferences(
                catalog(w.catalogs.assets, "/catalogs/assets"),
                catalog(w.catalogs.actions, "/catalogs/actions"),
            ),
            w.groups.mapIndexed { index, group ->
                SceneGroup(
                    localId(group.id, "/groups/$index/id"),
                    group.displayName,
                    group.editorVisible,
                )
            },
            w.elements.mapIndexed { index, element -> element(element, "/elements/$index") },
        )
    }

    private fun catalog(w: CatalogReferenceWire, path: String): CatalogReference {
        val id = catalogId(w.id, "$path/id")
        return mapped("$path/version", "INVALID_IDENTIFIER") { CatalogReference(id, w.version) }
    }

    private fun element(w: ElementWire, path: String): SceneElement =
        when (w) {
            is PropWire ->
                Prop(
                    localId(w.id, "$path/id"),
                    w.group?.let { localId(it, "$path/group") },
                    transform(w.transform, "$path/transform"),
                    w.visible,
                    activation(w.activation),
                    assetKey(w.asset, "$path/asset"),
                    w.initialAnimation?.let { localId(it, "$path/initialAnimation") },
                )
            is CompositePropWire ->
                CompositeProp(
                    localId(w.id, "$path/id"),
                    w.group?.let { localId(it, "$path/group") },
                    transform(w.transform, "$path/transform"),
                    w.visible,
                    activation(w.activation),
                    w.parts.mapIndexed { index, part ->
                        CompositePart(
                            localId(part.id, "$path/parts/$index/id"),
                            assetKey(part.asset, "$path/parts/$index/asset"),
                            transform(part.transform, "$path/parts/$index/transform"),
                        )
                    },
                )
            is NpcWire ->
                Npc(
                    localId(w.id, "$path/id"),
                    w.group?.let { localId(it, "$path/group") },
                    transform(w.transform, "$path/transform"),
                    w.visible,
                    activation(w.activation),
                    assetKey(w.body, "$path/body"),
                    w.label?.let(::component),
                    vec(w.labelOffset, "$path/labelOffset", "NON_FINITE_TRANSFORM"),
                    look(w.look, "$path/look"),
                    w.initialAnimation?.let { localId(it, "$path/initialAnimation") },
                    bounds(w.interactionBounds, "$path/interactionBounds"),
                    w.proximity?.let { proximity(it, "$path/proximity") },
                    w.bindings.mapIndexed { index, binding ->
                        binding(binding, "$path/bindings/$index")
                    },
                )
        }

    private fun activation(v: String) =
        when (v) {
            "AUTOMATIC" -> ActivationPolicy.AUTOMATIC
            "ALWAYS" -> ActivationPolicy.ALWAYS
            else -> error("WireReader accepted an invalid activation policy")
        }

    private fun transform(w: TransformWire, path: String): Transform {
        val position = vec(w.position, "$path/position", "NON_FINITE_TRANSFORM")
        val rotation = rotation(w.rotation, "$path/rotation")
        val scale = vec(w.scale, "$path/scale", "NON_FINITE_TRANSFORM")
        val invalidScale = firstComponent(w.scale) { it <= 0.0 }
        return mapped("$path/scale/${invalidScale ?: "x"}", "INVALID_SCALE") {
            Transform(position, rotation, scale)
        }
    }

    private fun rotation(w: RotationWire, path: String): EulerRotation =
        mapped("$path/${firstRotationComponent(w) ?: "yaw"}", "NON_FINITE_TRANSFORM") {
            EulerRotation(w.yaw, w.pitch, w.roll)
        }

    private fun vec(w: Vec3Wire, path: String, code: String): Vec3 =
        mapped("$path/${firstComponent(w) { !it.isFinite() } ?: "x"}", code) { Vec3(w.x, w.y, w.z) }

    private fun bounds(w: BoundsWire, path: String): LocalBounds {
        val center = vec(w.center, "$path/center", "INVALID_BOUNDS")
        val size = vec(w.size, "$path/size", "INVALID_BOUNDS")
        val invalidSize = firstComponent(w.size) { it <= 0.0 }
        return mapped("$path/size/${invalidSize ?: "x"}", "INVALID_BOUNDS") {
            LocalBounds(center, size)
        }
    }

    private fun look(w: LookWire, path: String): LookBehavior =
        when (w) {
            FixedLookWire -> LookBehavior.Fixed
            is TrackNearestLookWire ->
                mapped(
                    if (!w.maxDistance.isFinite() || w.maxDistance <= 0.0) "$path/maxDistance"
                    else "$path/maxTurnDegreesPerSecond",
                    "INVALID_ACTION_ARGUMENT",
                ) {
                    LookBehavior.TrackNearest(w.maxDistance, w.yawOnly, w.maxTurnDegreesPerSecond)
                }
        }

    private fun proximity(w: ProximityWire, path: String): ProximitySensor =
        mapped(
            if (!w.enterRadius.isFinite() || w.enterRadius <= 0.0) "$path/enterRadius"
            else "$path/exitRadius",
            "INVALID_ACTION_ARGUMENT",
        ) {
            ProximitySensor(w.enterRadius, w.exitRadius)
        }

    private fun binding(w: BindingWire, path: String): TriggerBinding {
        val actions =
            w.actions.mapIndexed { index, action -> action(action, "$path/actions/$index") }
        val pointer =
            when {
                w.cooldownMillis < 0 -> "$path/cooldownMillis"
                w.debounceMillis < 0 -> "$path/debounceMillis"
                actions.isEmpty() -> "$path/actions"
                else -> path
            }
        return mapped(pointer, "INVALID_ACTION_ARGUMENT") {
            TriggerBinding(
                trigger(w.trigger),
                w.conditions.mapIndexed { index, condition ->
                    condition(condition, "$path/conditions/$index")
                },
                w.cooldownMillis,
                w.debounceMillis,
                actions,
            )
        }
    }

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

    private fun condition(w: ConditionWire, path: String): SceneCondition =
        when (w) {
            is HandConditionWire ->
                HandCondition(if (w.hand == "MAIN") SceneHand.MAIN else SceneHand.OFF)
            is SneakingConditionWire -> SneakingCondition(w.sneaking)
            is PermissionConditionWire ->
                mapped("$path/permission", "INVALID_ACTION_ARGUMENT") {
                    PermissionCondition(w.permission)
                }
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

    private fun action(w: ActionWire, path: String): SceneAction =
        when (w) {
            is StartAnimationWire ->
                StartAnimationAction(
                    target(w.target, "$path/target"),
                    localId(w.animation, "$path/animation"),
                )
            is StopAnimationWire ->
                StopAnimationAction(
                    target(w.target, "$path/target"),
                    w.animation?.let { localId(it, "$path/animation") },
                )
            is PlaySoundWire ->
                mapped(
                    if (!w.volume.isFinite() || w.volume <= 0.0) "$path/volume" else "$path/pitch",
                    "INVALID_ACTION_ARGUMENT",
                ) {
                    PlaySoundAction(assetKey(w.sound, "$path/sound"), w.volume, w.pitch)
                }
            is SetViewerScaleWire ->
                mapped(
                    if (!w.multiplier.isFinite() || w.multiplier <= 0.0) "$path/multiplier"
                    else "$path/transitionMillis",
                    "INVALID_ACTION_ARGUMENT",
                ) {
                    SetViewerScaleAction(
                        target(w.target, "$path/target"),
                        w.multiplier,
                        w.transitionMillis,
                    )
                }
            is SetViewerHighlightWire ->
                mapped("$path/transitionMillis", "INVALID_ACTION_ARGUMENT") {
                    SetViewerHighlightAction(
                        target(w.target, "$path/target"),
                        w.enabled,
                        w.transitionMillis,
                    )
                }
            is SendMessageWire -> SendMessageAction(component(w.message))
            is SendActionBarWire -> SendActionBarAction(component(w.message))
            is ShowTitleWire ->
                mapped(
                    when {
                        w.fadeInMillis < 0 -> "$path/fadeInMillis"
                        w.stayMillis < 0 -> "$path/stayMillis"
                        else -> "$path/fadeOutMillis"
                    },
                    "INVALID_ACTION_ARGUMENT",
                ) {
                    ShowTitleAction(
                        component(w.title),
                        component(w.subtitle),
                        w.fadeInMillis,
                        w.stayMillis,
                        w.fadeOutMillis,
                    )
                }
            is EmitParticleWire ->
                mapped(
                    when {
                        w.count < 0 -> "$path/count"
                        !w.speed.isFinite() || w.speed < 0.0 -> "$path/speed"
                        else -> path
                    },
                    "INVALID_ACTION_ARGUMENT",
                ) {
                    EmitParticleAction(
                        target(w.target, "$path/target"),
                        assetKey(w.particle, "$path/particle"),
                        w.count,
                        vec(w.offset, "$path/offset", "INVALID_ACTION_ARGUMENT"),
                        w.speed,
                    )
                }
            is ApplicationWire ->
                ApplicationAction(
                    actionKey(w.key, "$path/key"),
                    w.arguments.entries.associate { (key, value) ->
                        localId(key, childPath("$path/arguments", key)) to
                            argument(value, childPath("$path/arguments", key))
                    },
                )
        }

    private fun target(w: TargetWire, path: String) =
        ElementTarget(
            localId(w.element, "$path/element"),
            w.part?.let { localId(it, "$path/part") },
        )

    private fun argument(w: ArgumentWire, path: String): ApplicationArgument =
        when (w) {
            is StringArgumentWire -> StringArgument(w.value)
            is LongArgumentWire -> LongArgument(w.value)
            is DecimalArgumentWire ->
                mapped("$path/value", "LIMIT_EXCEEDED") { DecimalArgument(w.value) }
            is BooleanArgumentWire -> BooleanArgument(w.value)
            is EnumArgumentWire -> EnumArgument(localId(w.value, "$path/value"))
            is AssetArgumentWire -> AssetArgument(assetKey(w.value, "$path/value"))
        }

    private fun sceneId(value: String, path: String) =
        mapped(path, "INVALID_IDENTIFIER") { SceneId(value) }

    private fun catalogId(value: String, path: String) =
        mapped(path, "INVALID_IDENTIFIER") { CatalogId(value) }

    private fun actionKey(value: String, path: String) =
        mapped(path, "INVALID_IDENTIFIER") { ActionKey(value) }

    private fun assetKey(value: String, path: String) =
        mapped(path, "INVALID_IDENTIFIER") { AssetKey(value) }

    private fun localId(value: String, path: String) =
        mapped(path, "INVALID_IDENTIFIER") { LocalId(value) }

    private inline fun <T> mapped(path: String, code: String, construct: () -> T): T =
        try {
            construct()
        } catch (_: IllegalArgumentException) {
            throw DecodeFailure(path, code, "Scene value violates a domain invariant.")
        }

    private fun firstComponent(value: Vec3Wire, predicate: (Double) -> Boolean): String? =
        when {
            predicate(value.x) -> "x"
            predicate(value.y) -> "y"
            predicate(value.z) -> "z"
            else -> null
        }

    private fun firstRotationComponent(value: RotationWire): String? =
        when {
            !value.yaw.isFinite() -> "yaw"
            !value.pitch.isFinite() -> "pitch"
            !value.roll.isFinite() -> "roll"
            else -> null
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
