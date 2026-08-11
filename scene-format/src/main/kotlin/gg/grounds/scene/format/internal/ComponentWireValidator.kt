package gg.grounds.scene.format.internal

import tools.jackson.databind.JsonNode

/** Validates authored Adventure JSON before the already-bounded tree is handed to Gson. */
internal object ComponentWireValidator {
    private val componentFields = setOf(
        "text", "translate", "fallback", "with", "score", "selector", "keybind", "nbt", "plain",
        "interpret", "block", "entity", "storage", "separator", "extra", "font", "color", "shadow_color",
        "bold", "italic", "underlined", "strikethrough", "obfuscated", "insertion",
        "click_event", "clickEvent", "hover_event", "hoverEvent",
    )
    private val contentFields = setOf("text", "translate", "score", "selector", "keybind", "nbt", "plain")
    private val clickFields = setOf("action", "value", "url", "path", "command", "page", "id", "payload")
    private val hoverFields = setOf("action", "contents", "value")
    private val scoreFields = setOf("name", "objective", "value")
    private val showEntityFields = setOf("type", "id", "uuid", "name")
    private val showItemFields = setOf("id", "count", "tag", "components")

    fun validate(node: JsonNode, path: String) {
        when {
            node.isString || node.isNumber || node.isBoolean -> return
            node.isArray -> {
                if (node.isEmpty) fail(path, "UNKNOWN_TYPE", "Component array must not be empty.")
                node.forEachIndexed { index, child -> validate(child, childPath(path, index.toString())) }
                return
            }
            !node.isObject -> fail(path, "MALFORMED_JSON", "Component must be a scalar, array, or object.")
        }

        rejectUnknown(node, componentFields, path, "component")
        val content = contentFields.filter { node.has(it) }
        if (content.size != 1) fail(path, "UNKNOWN_TYPE", "Component must declare exactly one supported content type.")

        when (content.single()) {
            "text" -> scalarString(node["text"], childPath(path, "text"))
            "translate" -> validateTranslation(node, path)
            "score" -> validateScore(node["score"], childPath(path, "score"))
            "selector" -> scalarString(node["selector"], childPath(path, "selector"))
            "keybind" -> scalarString(node["keybind"], childPath(path, "keybind"))
            "nbt", "plain" -> validateNbt(node, path)
        }

        optionalString(node, "font", path)
        optionalString(node, "color", path)
        optionalString(node, "insertion", path)
        optionalStringOrNumber(node, "shadow_color", path)
        listOf("bold", "italic", "underlined", "strikethrough", "obfuscated").forEach { optionalBoolean(node, it, path) }
        optionalComponents(node, "extra", path)
        optionalComponent(node, "separator", path)
        optionalClick(node, "click_event", path)
        optionalClick(node, "clickEvent", path)
        optionalHover(node, "hover_event", path)
        optionalHover(node, "hoverEvent", path)
    }

    private fun validateTranslation(node: JsonNode, path: String) {
        scalarString(node["translate"], childPath(path, "translate"))
        optionalString(node, "fallback", path)
        optionalComponents(node, "with", path)
    }

    private fun validateScore(node: JsonNode, path: String) {
        objectNode(node, path)
        rejectUnknown(node, scoreFields, path, "score")
        requiredString(node, "name", path)
        requiredString(node, "objective", path)
        optionalString(node, "value", path)
    }

    private fun validateNbt(node: JsonNode, path: String) {
        val contentName = if (node.has("nbt")) "nbt" else "plain"
        scalarString(node[contentName], childPath(path, contentName))
        optionalBoolean(node, "interpret", path)
        val sources = listOf("block", "entity", "storage").filter { node.has(it) }
        if (sources.size != 1) fail(path, "UNKNOWN_TYPE", "NBT component must declare exactly one source type.")
        scalarString(node[sources.single()], childPath(path, sources.single()))
    }

    private fun optionalClick(node: JsonNode, key: String, path: String) {
        if (!node.has(key)) return
        val eventPath = childPath(path, key)
        val event = node[key]
        objectNode(event, eventPath)
        rejectUnknown(event, clickFields, eventPath, "click event")
        requiredString(event, "action", eventPath)
        val payloads = clickFields.filter { it != "action" && event.has(it) }
        if (payloads.size != 1) fail(eventPath, "UNKNOWN_TYPE", "Click event must declare exactly one payload.")
        scalarString(event[payloads.single()], childPath(eventPath, payloads.single()))
    }

    private fun optionalHover(node: JsonNode, key: String, path: String) {
        if (!node.has(key)) return
        val eventPath = childPath(path, key)
        val event = node[key]
        objectNode(event, eventPath)
        rejectUnknown(event, hoverFields, eventPath, "hover event")
        val action = requiredString(event, "action", eventPath)
        val payloadName = listOf("contents", "value").singleOrNull { event.has(it) }
            ?: fail(eventPath, "UNKNOWN_TYPE", "Hover event must declare exactly one payload.")
        val payload = event[payloadName]
        val payloadPath = childPath(eventPath, payloadName)
        when (action) {
            "show_text" -> validate(payload, payloadPath)
            "show_entity" -> validateShowEntity(payload, payloadPath)
            "show_item" -> validateShowItem(payload, payloadPath)
            else -> fail(childPath(eventPath, "action"), "UNKNOWN_TYPE", "Unknown hover event type.")
        }
    }

    private fun validateShowEntity(node: JsonNode, path: String) {
        objectNode(node, path)
        rejectUnknown(node, showEntityFields, path, "show-entity payload")
        requiredString(node, "type", path)
        if (!node.has("id") && !node.has("uuid")) fail(childPath(path, "id"), "MALFORMED_JSON", "Show-entity payload requires an id.")
        optionalString(node, "id", path)
        optionalString(node, "uuid", path)
        optionalComponent(node, "name", path)
    }

    private fun validateShowItem(node: JsonNode, path: String) {
        objectNode(node, path)
        rejectUnknown(node, showItemFields, path, "show-item payload")
        requiredString(node, "id", path)
        if (node.has("count") && !node["count"].isIntegralNumber) fail(childPath(path, "count"), "MALFORMED_JSON", "Item count must be an integer.")
        optionalString(node, "tag", path)
        if (node.has("components") && !node["components"].isObject) fail(childPath(path, "components"), "MALFORMED_JSON", "Item components must be an object.")
    }

    private fun optionalComponents(node: JsonNode, key: String, path: String) {
        if (!node.has(key)) return
        val arrayPath = childPath(path, key)
        val values = node[key]
        if (!values.isArray) fail(arrayPath, "MALFORMED_JSON", "Component property must be an array.")
        values.forEachIndexed { index, child -> validate(child, childPath(arrayPath, index.toString())) }
    }

    private fun optionalComponent(node: JsonNode, key: String, path: String) {
        if (node.has(key)) validate(node[key], childPath(path, key))
    }

    private fun requiredString(node: JsonNode, key: String, path: String): String {
        if (!node.has(key)) fail(childPath(path, key), "MALFORMED_JSON", "Required component property is missing.")
        return scalarString(node[key], childPath(path, key))
    }

    private fun optionalString(node: JsonNode, key: String, path: String) {
        if (node.has(key)) scalarString(node[key], childPath(path, key))
    }

    private fun optionalStringOrNumber(node: JsonNode, key: String, path: String) {
        if (node.has(key) && !node[key].isString && !node[key].isNumber) {
            fail(childPath(path, key), "MALFORMED_JSON", "Component property must be a string or number.")
        }
    }

    private fun optionalBoolean(node: JsonNode, key: String, path: String) {
        if (node.has(key) && !node[key].isBoolean) fail(childPath(path, key), "MALFORMED_JSON", "Component property must be a boolean.")
    }

    private fun scalarString(node: JsonNode, path: String): String {
        if (!node.isString) fail(path, "MALFORMED_JSON", "Component property must be a string.")
        return node.stringValue()
    }

    private fun objectNode(node: JsonNode, path: String) {
        if (!node.isObject) fail(path, "MALFORMED_JSON", "Component property must be an object.")
    }

    private fun rejectUnknown(node: JsonNode, allowed: Set<String>, path: String, noun: String) {
        node.properties().firstOrNull { it.key !in allowed }?.let {
            fail(childPath(path, it.key), "UNKNOWN_FIELD", "Unknown $noun field.")
        }
    }

    private fun fail(path: String, code: String, message: String): Nothing = throw DecodeFailure(path, code, message)
}
