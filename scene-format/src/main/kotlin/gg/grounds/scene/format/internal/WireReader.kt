package gg.grounds.scene.format.internal

import java.math.BigDecimal
import tools.jackson.databind.JsonNode

/** Strict tree-to-wire conversion keeps Jackson's types out of the public model. */
internal object WireReader {
    fun readScene(node: JsonNode): SceneWire = obj(
        node,
        setOf("schemaVersion", "id", "metadata", "catalogs", "groups", "elements"),
        "/",
    ) { value ->
        SceneWire(
            integer(value, "schemaVersion", "/"),
            string(value, "id", "/"),
            metadata(value["metadata"], "/metadata"),
            catalogs(value["catalogs"], "/catalogs"),
            array(value["groups"], "/groups").mapIndexed { index, child -> group(child, "/groups/$index") },
            array(value["elements"], "/elements").mapIndexed { index, child -> element(child, "/elements/$index") },
        )
    }

    private fun metadata(node: JsonNode, path: String) = obj(node, setOf("name", "description", "tags"), path) { value ->
        MetadataWire(
            string(value, "name", path),
            nullableString(value, "description", path),
            array(value["tags"], childPath(path, "tags")).mapIndexed { index, child -> text(child, childPath(childPath(path, "tags"), index.toString())) },
        )
    }

    private fun catalogs(node: JsonNode, path: String) = obj(node, setOf("assets", "actions"), path) { value ->
        CatalogsWire(
            catalog(value["assets"], childPath(path, "assets")),
            catalog(value["actions"], childPath(path, "actions")),
        )
    }

    private fun catalog(node: JsonNode, path: String) = obj(node, setOf("id", "version"), path) { value ->
        CatalogReferenceWire(string(value, "id", path), string(value, "version", path))
    }

    private fun group(node: JsonNode, path: String) = obj(node, setOf("id", "displayName", "editorVisible"), path) { value ->
        GroupWire(string(value, "id", path), string(value, "displayName", path), boolean(value, "editorVisible", path))
    }

    private fun element(node: JsonNode, path: String): ElementWire {
        val discriminator = type(node, path, "element", setOf("prop", "composite_prop", "npc"))
        val base = setOf("type", "id", "group", "transform", "visible", "activation")
        return when (discriminator) {
            "prop" -> obj(node, base + setOf("asset", "initialAnimation"), path) { value ->
                PropWire(
                    string(value, "id", path), nullableString(value, "group", path),
                    transform(value["transform"], childPath(path, "transform")), boolean(value, "visible", path),
                    enum(value, "activation", path, ACTIVATION_NAMES), string(value, "asset", path),
                    nullableString(value, "initialAnimation", path),
                )
            }
            "composite_prop" -> obj(node, base + "parts", path) { value ->
                CompositePropWire(
                    string(value, "id", path), nullableString(value, "group", path),
                    transform(value["transform"], childPath(path, "transform")), boolean(value, "visible", path),
                    enum(value, "activation", path, ACTIVATION_NAMES),
                    array(value["parts"], childPath(path, "parts")).mapIndexed { index, child -> part(child, childPath(childPath(path, "parts"), index.toString())) },
                )
            }
            else -> obj(node, base + setOf("body", "label", "labelOffset", "look", "initialAnimation", "interactionBounds", "proximity", "bindings"), path) { value ->
                NpcWire(
                    string(value, "id", path), nullableString(value, "group", path),
                    transform(value["transform"], childPath(path, "transform")), boolean(value, "visible", path),
                    enum(value, "activation", path, ACTIVATION_NAMES), string(value, "body", path),
                    nullableComponent(value, "label", path), vec(value["labelOffset"], childPath(path, "labelOffset")),
                    look(value["look"], childPath(path, "look")), nullableString(value, "initialAnimation", path),
                    bounds(value["interactionBounds"], childPath(path, "interactionBounds")),
                    nullable(value, "proximity", path, ::proximity),
                    array(value["bindings"], childPath(path, "bindings")).mapIndexed { index, child -> binding(child, childPath(childPath(path, "bindings"), index.toString())) },
                )
            }
        }
    }

    private fun part(node: JsonNode, path: String) = obj(node, setOf("id", "asset", "transform"), path) { value ->
        CompositePartWire(string(value, "id", path), string(value, "asset", path), transform(value["transform"], childPath(path, "transform")))
    }

    private fun transform(node: JsonNode, path: String) = obj(node, setOf("position", "rotation", "scale"), path) { value ->
        TransformWire(vec(value["position"], childPath(path, "position")), rotation(value["rotation"], childPath(path, "rotation")), vec(value["scale"], childPath(path, "scale")))
    }

    private fun vec(node: JsonNode, path: String) = obj(node, setOf("x", "y", "z"), path) { value ->
        Vec3Wire(double(value, "x", path), double(value, "y", path), double(value, "z", path))
    }

    private fun rotation(node: JsonNode, path: String) = obj(node, setOf("yaw", "pitch", "roll"), path) { value ->
        RotationWire(double(value, "yaw", path), double(value, "pitch", path), double(value, "roll", path))
    }

    private fun bounds(node: JsonNode, path: String) = obj(node, setOf("center", "size"), path) { value ->
        BoundsWire(vec(value["center"], childPath(path, "center")), vec(value["size"], childPath(path, "size")))
    }

    private fun look(node: JsonNode, path: String): LookWire = when (type(node, path, "look", setOf("fixed", "track_nearest"))) {
        "fixed" -> obj(node, setOf("type"), path) { FixedLookWire }
        else -> obj(node, setOf("type", "maxDistance", "yawOnly", "maxTurnDegreesPerSecond"), path) { value ->
            TrackNearestLookWire(double(value, "maxDistance", path), boolean(value, "yawOnly", path), double(value, "maxTurnDegreesPerSecond", path))
        }
    }

    private fun proximity(node: JsonNode, path: String) = obj(node, setOf("enterRadius", "exitRadius"), path) { value ->
        ProximityWire(double(value, "enterRadius", path), double(value, "exitRadius", path))
    }

    private fun binding(node: JsonNode, path: String) = obj(node, setOf("trigger", "conditions", "cooldownMillis", "debounceMillis", "actions"), path) { value ->
        BindingWire(
            enum(value, "trigger", path, TRIGGER_NAMES),
            array(value["conditions"], childPath(path, "conditions")).mapIndexed { index, child -> condition(child, childPath(childPath(path, "conditions"), index.toString())) },
            long(value, "cooldownMillis", path), long(value, "debounceMillis", path),
            array(value["actions"], childPath(path, "actions")).mapIndexed { index, child -> action(child, childPath(childPath(path, "actions"), index.toString())) },
        )
    }

    private fun condition(node: JsonNode, path: String): ConditionWire = when (type(node, path, "condition", CONDITION_TYPES)) {
        "hand" -> obj(node, setOf("type", "hand"), path) { HandConditionWire(enum(it, "hand", path, HAND_NAMES)) }
        "sneaking" -> obj(node, setOf("type", "sneaking"), path) { SneakingConditionWire(boolean(it, "sneaking", path)) }
        "permission" -> obj(node, setOf("type", "permission"), path) { PermissionConditionWire(string(it, "permission", path)) }
        else -> obj(node, setOf("type", "gameMode"), path) { GameModeConditionWire(enum(it, "gameMode", path, GAME_MODE_NAMES)) }
    }

    private fun action(node: JsonNode, path: String): ActionWire = when (type(node, path, "action", ACTION_TYPES)) {
        "start_animation" -> obj(node, setOf("type", "target", "animation"), path) { StartAnimationWire(target(it["target"], childPath(path, "target")), string(it, "animation", path)) }
        "stop_animation" -> obj(node, setOf("type", "target", "animation"), path) { StopAnimationWire(target(it["target"], childPath(path, "target")), nullableString(it, "animation", path)) }
        "play_sound" -> obj(node, setOf("type", "sound", "volume", "pitch"), path) { PlaySoundWire(string(it, "sound", path), double(it, "volume", path), double(it, "pitch", path)) }
        "set_viewer_scale" -> obj(node, setOf("type", "target", "multiplier", "transitionMillis"), path) { SetViewerScaleWire(target(it["target"], childPath(path, "target")), double(it, "multiplier", path), long(it, "transitionMillis", path)) }
        "set_viewer_highlight" -> obj(node, setOf("type", "target", "enabled", "transitionMillis"), path) { SetViewerHighlightWire(target(it["target"], childPath(path, "target")), boolean(it, "enabled", path), long(it, "transitionMillis", path)) }
        "send_message" -> obj(node, setOf("type", "message"), path) { SendMessageWire(component(it, "message", path)) }
        "send_action_bar" -> obj(node, setOf("type", "message"), path) { SendActionBarWire(component(it, "message", path)) }
        "show_title" -> obj(node, setOf("type", "title", "subtitle", "fadeInMillis", "stayMillis", "fadeOutMillis"), path) {
            ShowTitleWire(component(it, "title", path), component(it, "subtitle", path), long(it, "fadeInMillis", path), long(it, "stayMillis", path), long(it, "fadeOutMillis", path))
        }
        "emit_particle" -> obj(node, setOf("type", "target", "particle", "count", "offset", "speed"), path) {
            EmitParticleWire(target(it["target"], childPath(path, "target")), string(it, "particle", path), integer(it, "count", path), vec(it["offset"], childPath(path, "offset")), double(it, "speed", path))
        }
        else -> obj(node, setOf("type", "key", "arguments"), path) { value ->
            ApplicationWire(
                string(value, "key", path),
                objectMap(value["arguments"], childPath(path, "arguments")).mapValues { (key, child) -> argument(child, childPath(childPath(path, "arguments"), key)) },
            )
        }
    }

    private fun target(node: JsonNode, path: String) = obj(node, setOf("element", "part"), path) { value ->
        TargetWire(string(value, "element", path), nullableString(value, "part", path))
    }

    private fun argument(node: JsonNode, path: String): ArgumentWire = when (type(node, path, "argument", ARGUMENT_TYPES)) {
        "string" -> obj(node, setOf("type", "value"), path) { StringArgumentWire(string(it, "value", path)) }
        "long" -> obj(node, setOf("type", "value"), path) { LongArgumentWire(long(it, "value", path)) }
        "decimal" -> obj(node, setOf("type", "value"), path) { DecimalArgumentWire(decimal(it, "value", path)) }
        "boolean" -> obj(node, setOf("type", "value"), path) { BooleanArgumentWire(boolean(it, "value", path)) }
        "enum" -> obj(node, setOf("type", "value"), path) { EnumArgumentWire(string(it, "value", path)) }
        else -> obj(node, setOf("type", "value"), path) { AssetArgumentWire(string(it, "value", path)) }
    }

    private fun type(node: JsonNode, path: String, noun: String, valid: Set<String>): String {
        obj(node, null, path) { }
        if (!node.has("type")) fail(childPath(path, "type"), "MALFORMED_JSON", "Required field is missing.")
        val value = string(node, "type", path)
        if (value !in valid) fail(childPath(path, "type"), "UNKNOWN_TYPE", "Unknown $noun type.")
        return value
    }

    private fun enum(node: JsonNode, key: String, path: String, values: Set<String>): String {
        val value = string(node, key, path)
        if (value !in values) fail(childPath(path, key), "UNKNOWN_TYPE", "Unknown enum value.")
        return value
    }

    private fun <T> obj(node: JsonNode, fields: Set<String>?, path: String, read: (JsonNode) -> T): T {
        if (!node.isObject) fail(path, "MALFORMED_JSON", "Expected an object.")
        if (fields != null) {
            val names = node.properties().map { it.key }.toSet()
            val unknown = names.firstOrNull { it !in fields }
            if (unknown != null) fail(childPath(path, unknown), "UNKNOWN_FIELD", "Unknown field.")
            val missing = fields.firstOrNull { it !in names }
            if (missing != null) fail(childPath(path, missing), "MALFORMED_JSON", "Required field is missing.")
        }
        return read(node)
    }

    private fun array(node: JsonNode, path: String): List<JsonNode> {
        if (!node.isArray) fail(path, "MALFORMED_JSON", "Expected an array.")
        return node.toList()
    }

    private fun objectMap(node: JsonNode, path: String): Map<String, JsonNode> {
        if (!node.isObject) fail(path, "MALFORMED_JSON", "Expected an object.")
        return node.properties().associate { it.key to it.value }
    }

    private fun string(node: JsonNode, key: String, path: String) = text(node[key], childPath(path, key))

    private fun text(node: JsonNode, path: String): String {
        if (!node.isString) fail(path, "MALFORMED_JSON", "Expected a string.")
        return node.stringValue()
    }

    private fun nullableString(node: JsonNode, key: String, path: String): String? =
        if (node[key].isNull) null else text(node[key], childPath(path, key))

    private fun <T> nullable(node: JsonNode, key: String, path: String, read: (JsonNode, String) -> T): T? =
        if (node[key].isNull) null else read(node[key], childPath(path, key))

    private fun nullableComponent(node: JsonNode, key: String, path: String): JsonNode? =
        if (node[key].isNull) null else component(node, key, path)

    private fun component(node: JsonNode, key: String, path: String): JsonNode {
        val value = node[key]
        ComponentWireValidator.validate(value, childPath(path, key))
        return value
    }

    private fun boolean(node: JsonNode, key: String, path: String): Boolean {
        val value = node[key]
        if (!value.isBoolean) fail(childPath(path, key), "MALFORMED_JSON", "Expected a boolean.")
        return value.booleanValue()
    }

    private fun double(node: JsonNode, key: String, path: String): Double {
        val value = node[key]
        if (!value.isNumber || !value.doubleValue().isFinite()) fail(childPath(path, key), "MALFORMED_JSON", "Expected a finite number.")
        return value.doubleValue()
    }

    private fun integer(node: JsonNode, key: String, path: String): Int {
        val value = node[key]
        if (!value.isIntegralNumber || !value.canConvertToInt()) fail(childPath(path, key), "MALFORMED_JSON", "Expected an integer.")
        return value.intValue()
    }

    private fun long(node: JsonNode, key: String, path: String): Long {
        val value = node[key]
        if (!value.isIntegralNumber || !value.canConvertToLong()) fail(childPath(path, key), "MALFORMED_JSON", "Expected an integer.")
        return value.longValue()
    }

    private fun decimal(node: JsonNode, key: String, path: String): BigDecimal {
        val value = node[key]
        if (!value.isNumber) fail(childPath(path, key), "MALFORMED_JSON", "Expected a number.")
        return value.decimalValue()
    }

    private fun fail(path: String, code: String, message: String): Nothing = throw DecodeFailure(path, code, message)

    private val ACTIVATION_NAMES = setOf("AUTOMATIC", "ALWAYS")
    private val TRIGGER_NAMES = setOf("LEFT_CLICK", "RIGHT_CLICK", "HOVER_ENTER", "HOVER_LEAVE", "PROXIMITY_ENTER", "PROXIMITY_LEAVE")
    private val HAND_NAMES = setOf("MAIN", "OFF")
    private val GAME_MODE_NAMES = setOf("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR")
    private val CONDITION_TYPES = setOf("hand", "sneaking", "permission", "game_mode")
    private val ACTION_TYPES = setOf("start_animation", "stop_animation", "play_sound", "set_viewer_scale", "set_viewer_highlight", "send_message", "send_action_bar", "show_title", "emit_particle", "application")
    private val ARGUMENT_TYPES = setOf("string", "long", "decimal", "boolean", "enum", "asset")
}
