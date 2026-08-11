package gg.grounds.scene.format.internal

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Canonical, UTF-8 JSON writer for the internal v1 wire model. */
internal object CanonicalJson {
    private val mapper = JsonMapper.builder().build()

    fun write(scene: SceneWire): ByteArray =
        Writer()
            .apply {
                value(scene, 0)
                append('\n')
            }
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

    fun compact(value: Any): String = Writer().apply { value(value, 0, compact = true) }.toString()

    fun canonicalize(node: JsonNode): JsonNode =
        when {
            node.isObject ->
                mapper.createObjectNode().also { result ->
                    node.properties().sortedWith(compareBy(CODE_POINT_ORDER) { it.key }).forEach {
                        result.set(it.key, canonicalize(it.value))
                    }
                }
            node.isArray ->
                mapper.createArrayNode().also { result ->
                    node.forEach { result.add(canonicalize(it)) }
                }
            else -> node
        }

    private class Writer {
        private val output = StringBuilder()

        fun append(value: Any?) {
            output.append(value)
        }

        override fun toString(): String = output.toString()

        fun value(value: Any?, depth: Int, compact: Boolean = false) {
            when (value) {
                null -> append("null")
                is String -> string(value)
                is Boolean,
                is Int,
                is Long -> append(value)
                is Double -> append(decimal(BigDecimal.valueOf(value)))
                is BigDecimal -> append(decimal(value))
                is JsonNode -> node(value, depth, compact)
                is List<*> -> array(value, depth, compact)
                is SceneWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "schemaVersion" to value.schemaVersion,
                            "id" to value.id,
                            "metadata" to value.metadata,
                            "catalogs" to value.catalogs,
                            "groups" to value.groups,
                            "elements" to value.elements,
                        ),
                    )
                is MetadataWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "name" to value.name,
                            "description" to value.description,
                            "tags" to value.tags,
                        ),
                    )
                is CatalogsWire ->
                    obj(
                        depth,
                        compact,
                        listOf("assets" to value.assets, "actions" to value.actions),
                    )
                is CatalogReferenceWire ->
                    obj(depth, compact, listOf("id" to value.id, "version" to value.version))
                is GroupWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "id" to value.id,
                            "displayName" to value.displayName,
                            "editorVisible" to value.editorVisible,
                        ),
                    )
                is Vec3Wire ->
                    obj(depth, compact, listOf("x" to value.x, "y" to value.y, "z" to value.z))
                is RotationWire ->
                    obj(
                        depth,
                        compact,
                        listOf("yaw" to value.yaw, "pitch" to value.pitch, "roll" to value.roll),
                    )
                is TransformWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "position" to value.position,
                            "rotation" to value.rotation,
                            "scale" to value.scale,
                        ),
                    )
                is BoundsWire ->
                    obj(depth, compact, listOf("center" to value.center, "size" to value.size))
                is PropWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "id" to value.id,
                            "group" to value.group,
                            "transform" to value.transform,
                            "visible" to value.visible,
                            "activation" to value.activation,
                            "asset" to value.asset,
                            "initialAnimation" to value.initialAnimation,
                        ),
                    )
                is CompositePartWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "id" to value.id,
                            "asset" to value.asset,
                            "transform" to value.transform,
                        ),
                    )
                is CompositePropWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "id" to value.id,
                            "group" to value.group,
                            "transform" to value.transform,
                            "visible" to value.visible,
                            "activation" to value.activation,
                            "parts" to value.parts,
                        ),
                    )
                is NpcWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "id" to value.id,
                            "group" to value.group,
                            "transform" to value.transform,
                            "visible" to value.visible,
                            "activation" to value.activation,
                            "body" to value.body,
                            "label" to value.label,
                            "labelOffset" to value.labelOffset,
                            "look" to value.look,
                            "initialAnimation" to value.initialAnimation,
                            "interactionBounds" to value.interactionBounds,
                            "proximity" to value.proximity,
                            "bindings" to value.bindings,
                        ),
                    )
                is FixedLookWire -> obj(depth, compact, listOf("type" to value.type))
                is TrackNearestLookWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "maxDistance" to value.maxDistance,
                            "yawOnly" to value.yawOnly,
                            "maxTurnDegreesPerSecond" to value.maxTurnDegreesPerSecond,
                        ),
                    )
                is ProximityWire ->
                    obj(
                        depth,
                        compact,
                        listOf("enterRadius" to value.enterRadius, "exitRadius" to value.exitRadius),
                    )
                is BindingWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "trigger" to value.trigger,
                            "conditions" to value.conditions,
                            "cooldownMillis" to value.cooldownMillis,
                            "debounceMillis" to value.debounceMillis,
                            "actions" to value.actions,
                        ),
                    )
                is HandConditionWire ->
                    obj(depth, compact, listOf("type" to value.type, "hand" to value.hand))
                is SneakingConditionWire ->
                    obj(depth, compact, listOf("type" to value.type, "sneaking" to value.sneaking))
                is PermissionConditionWire ->
                    obj(
                        depth,
                        compact,
                        listOf("type" to value.type, "permission" to value.permission),
                    )
                is GameModeConditionWire ->
                    obj(depth, compact, listOf("type" to value.type, "gameMode" to value.gameMode))
                is StartAnimationWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "target" to value.target,
                            "animation" to value.animation,
                        ),
                    )
                is StopAnimationWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "target" to value.target,
                            "animation" to value.animation,
                        ),
                    )
                is PlaySoundWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "sound" to value.sound,
                            "volume" to value.volume,
                            "pitch" to value.pitch,
                        ),
                    )
                is SetViewerScaleWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "target" to value.target,
                            "multiplier" to value.multiplier,
                            "transitionMillis" to value.transitionMillis,
                        ),
                    )
                is SetViewerHighlightWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "target" to value.target,
                            "enabled" to value.enabled,
                            "transitionMillis" to value.transitionMillis,
                        ),
                    )
                is SendMessageWire ->
                    obj(depth, compact, listOf("type" to value.type, "message" to value.message))
                is SendActionBarWire ->
                    obj(depth, compact, listOf("type" to value.type, "message" to value.message))
                is ShowTitleWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "title" to value.title,
                            "subtitle" to value.subtitle,
                            "fadeInMillis" to value.fadeInMillis,
                            "stayMillis" to value.stayMillis,
                            "fadeOutMillis" to value.fadeOutMillis,
                        ),
                    )
                is EmitParticleWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "target" to value.target,
                            "particle" to value.particle,
                            "count" to value.count,
                            "offset" to value.offset,
                            "speed" to value.speed,
                        ),
                    )
                is ApplicationWire ->
                    obj(
                        depth,
                        compact,
                        listOf(
                            "type" to value.type,
                            "key" to value.key,
                            "arguments" to value.arguments,
                        ),
                    )
                is TargetWire ->
                    obj(depth, compact, listOf("element" to value.element, "part" to value.part))
                is Map<*, *> ->
                    obj(
                        depth,
                        compact,
                        value.entries
                            .sortedWith(compareBy(CODE_POINT_ORDER) { it.key as String })
                            .map { (it.key as String) to it.value },
                    )
                is StringArgumentWire ->
                    obj(depth, compact, listOf("type" to value.type, "value" to value.value))
                is LongArgumentWire ->
                    obj(depth, compact, listOf("type" to value.type, "value" to value.value))
                is DecimalArgumentWire ->
                    obj(depth, compact, listOf("type" to value.type, "value" to value.value))
                is BooleanArgumentWire ->
                    obj(depth, compact, listOf("type" to value.type, "value" to value.value))
                is EnumArgumentWire ->
                    obj(depth, compact, listOf("type" to value.type, "value" to value.value))
                is AssetArgumentWire ->
                    obj(depth, compact, listOf("type" to value.type, "value" to value.value))
                else -> error("Unsupported canonical JSON value: ${value::class.qualifiedName}")
            }
        }

        private fun obj(depth: Int, compact: Boolean, fields: List<Pair<String, Any?>>) {
            append('{')
            fields.forEachIndexed { index, (name, field) ->
                if (index > 0) append(',')
                spacing(depth + 1, compact)
                string(name)
                append(if (compact) ':' else ": ")
                value(field, depth + 1, compact)
            }
            if (fields.isNotEmpty()) spacing(depth, compact)
            append('}')
        }

        private fun array(values: List<*>, depth: Int, compact: Boolean) {
            append('[')
            values.forEachIndexed { index, item ->
                if (index > 0) append(',')
                spacing(depth + 1, compact)
                value(item, depth + 1, compact)
            }
            if (values.isNotEmpty()) spacing(depth, compact)
            append(']')
        }

        private fun node(node: JsonNode, depth: Int, compact: Boolean) =
            when {
                node.isObject ->
                    obj(
                        depth,
                        compact,
                        node.properties().sortedWith(compareBy(CODE_POINT_ORDER) { it.key }).map {
                            it.key to it.value
                        },
                    )
                node.isArray -> array(node.toList(), depth, compact)
                node.isString -> string(node.stringValue())
                node.isBoolean -> append(node.booleanValue())
                node.isNumber -> append(decimal(node.decimalValue()))
                node.isNull -> append("null")
                else -> error("Unsupported JSON node")
            }

        private fun spacing(depth: Int, compact: Boolean) {
            if (!compact) {
                append('\n')
                repeat(depth) { append("  ") }
            }
        }

        private fun string(value: String) {
            append('"')
            var index = 0
            while (index < value.length) {
                val char = value[index]
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        when {
                            char.isHighSurrogate() &&
                                index + 1 < value.length &&
                                value[index + 1].isLowSurrogate() -> {
                                append(char)
                                append(value[++index])
                            }
                            char.isHighSurrogate() || char.isLowSurrogate() || char.code < 0x20 ->
                                unicodeEscape(char)
                            else -> append(char)
                        }
                }
                index++
            }
            append('"')
        }

        private fun unicodeEscape(char: Char) =
            append("\\u${char.code.toString(16).uppercase(java.util.Locale.ROOT).padStart(4, '0')}")
    }

    private fun decimal(value: BigDecimal): String =
        if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()
}
