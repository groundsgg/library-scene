package gg.grounds.scene.format.internal

import java.math.BigDecimal
import tools.jackson.databind.JsonNode

/** Strict tree-to-wire conversion keeps Jackson's types out of the public model. */
internal object WireReader {
    fun readScene(n: JsonNode) = obj(n, setOf("schemaVersion", "id", "metadata", "catalogs", "groups", "elements"), "/") { o ->
        SceneWire(i(o, "schemaVersion"), s(o, "id"), metadata(o["metadata"]), catalogs(o["catalogs"]), a(o["groups"]).map(::group), a(o["elements"]).map(::element))
    }
    private fun metadata(n: JsonNode) = obj(n, setOf("name", "description", "tags"), "/metadata") { o -> MetadataWire(s(o, "name"), nullableString(o, "description"), a(o["tags"]).map { text(it) }) }
    private fun catalogs(n: JsonNode) = obj(n, setOf("assets", "actions"), "/catalogs") { o -> CatalogsWire(catalog(o["assets"]), catalog(o["actions"])) }
    private fun catalog(n: JsonNode) = obj(n, setOf("id", "version"), "") { o -> CatalogReferenceWire(s(o, "id"), s(o, "version")) }
    private fun group(n: JsonNode) = obj(n, setOf("id", "displayName", "editorVisible"), "") { o -> GroupWire(s(o, "id"), s(o, "displayName"), b(o, "editorVisible")) }
    private fun element(n: JsonNode): ElementWire {
        val type = type(n, "element", setOf("prop", "composite_prop", "npc"))
        val base = setOf("type", "id", "group", "transform", "visible", "activation")
        return when (type) {
            "prop" -> obj(n, base + setOf("asset", "initialAnimation"), "") { o -> PropWire(s(o,"id"), nullableString(o,"group"), transform(o["transform"]), b(o,"visible"), s(o,"activation"), s(o,"asset"), nullableString(o,"initialAnimation")) }
            "composite_prop" -> obj(n, base + "parts", "") { o -> CompositePropWire(s(o,"id"), nullableString(o,"group"), transform(o["transform"]), b(o,"visible"), s(o,"activation"), a(o["parts"]).map(::part)) }
            else -> obj(n, base + setOf("body","label","labelOffset","look","initialAnimation","interactionBounds","proximity","bindings"), "") { o -> NpcWire(s(o,"id"), nullableString(o,"group"), transform(o["transform"]), b(o,"visible"), s(o,"activation"), s(o,"body"), nullableString(o,"label"), vec(o["labelOffset"]), look(o["look"]), nullableString(o,"initialAnimation"), bounds(o["interactionBounds"]), nullable(o,"proximity",::proximity), a(o["bindings"]).map(::binding)) }
        }
    }
    private fun part(n: JsonNode) = obj(n, setOf("id","asset","transform"), "") { o -> CompositePartWire(s(o,"id"),s(o,"asset"),transform(o["transform"])) }
    private fun transform(n: JsonNode) = obj(n, setOf("position","rotation","scale"), "") { o -> TransformWire(vec(o["position"]), rotation(o["rotation"]), vec(o["scale"])) }
    private fun vec(n: JsonNode) = obj(n, setOf("x","y","z"), "") { o -> Vec3Wire(d(o,"x"),d(o,"y"),d(o,"z")) }
    private fun rotation(n: JsonNode) = obj(n, setOf("yaw","pitch","roll"), "") { o -> RotationWire(d(o,"yaw"),d(o,"pitch"),d(o,"roll")) }
    private fun bounds(n: JsonNode) = obj(n, setOf("center","size"), "") { o -> BoundsWire(vec(o["center"]),vec(o["size"])) }
    private fun look(n: JsonNode): LookWire = when(type(n,"look",setOf("fixed","track_nearest"))) {
        "fixed" -> obj(n,setOf("type"),"") { FixedLookWire }
        else -> obj(n,setOf("type","maxDistance","yawOnly","maxTurnDegreesPerSecond"),"") { o -> TrackNearestLookWire(d(o,"maxDistance"),b(o,"yawOnly"),d(o,"maxTurnDegreesPerSecond")) }
    }
    private fun proximity(n: JsonNode) = obj(n,setOf("enterRadius","exitRadius"),"") { o -> ProximityWire(d(o,"enterRadius"),d(o,"exitRadius")) }
    private fun binding(n: JsonNode) = obj(n,setOf("trigger","conditions","cooldownMillis","debounceMillis","actions"),"") { o -> BindingWire(s(o,"trigger"),a(o["conditions"]).map(::condition),l(o,"cooldownMillis"),l(o,"debounceMillis"),a(o["actions"]).map(::action)) }
    private fun condition(n: JsonNode): ConditionWire = when(type(n,"condition",setOf("hand","sneaking","permission","game_mode"))) {
        "hand" -> obj(n,setOf("type","hand"),"") { o -> HandConditionWire(s(o,"hand")) }
        "sneaking" -> obj(n,setOf("type","sneaking"),"") { o -> SneakingConditionWire(b(o,"sneaking")) }
        "permission" -> obj(n,setOf("type","permission"),"") { o -> PermissionConditionWire(s(o,"permission")) }
        else -> obj(n,setOf("type","gameMode"),"") { o -> GameModeConditionWire(s(o,"gameMode")) }
    }
    private fun action(n: JsonNode): ActionWire = when(type(n,"action",ACTION_TYPES)) {
        "start_animation" -> obj(n,setOf("type","target","animation"),"") { o -> StartAnimationWire(target(o["target"]),s(o,"animation")) }
        "stop_animation" -> obj(n,setOf("type","target","animation"),"") { o -> StopAnimationWire(target(o["target"]),nullableString(o,"animation")) }
        "play_sound" -> obj(n,setOf("type","sound","volume","pitch"),"") { o -> PlaySoundWire(s(o,"sound"),d(o,"volume"),d(o,"pitch")) }
        "set_viewer_scale" -> obj(n,setOf("type","target","multiplier","transitionMillis"),"") { o -> SetViewerScaleWire(target(o["target"]),d(o,"multiplier"),l(o,"transitionMillis")) }
        "set_viewer_highlight" -> obj(n,setOf("type","target","enabled","transitionMillis"),"") { o -> SetViewerHighlightWire(target(o["target"]),b(o,"enabled"),l(o,"transitionMillis")) }
        "send_message" -> obj(n,setOf("type","message"),"") { o -> SendMessageWire(s(o,"message")) }
        "send_action_bar" -> obj(n,setOf("type","message"),"") { o -> SendActionBarWire(s(o,"message")) }
        "show_title" -> obj(n,setOf("type","title","subtitle","fadeInMillis","stayMillis","fadeOutMillis"),"") { o -> ShowTitleWire(s(o,"title"),s(o,"subtitle"),l(o,"fadeInMillis"),l(o,"stayMillis"),l(o,"fadeOutMillis")) }
        "emit_particle" -> obj(n,setOf("type","target","particle","count","offset","speed"),"") { o -> EmitParticleWire(target(o["target"]),s(o,"particle"),i(o,"count"),vec(o["offset"]),d(o,"speed")) }
        else -> obj(n,setOf("type","key","arguments"),"") { o -> ApplicationWire(s(o,"key"), map(o["arguments"]).mapValues { argument(it.value) }) }
    }
    private fun target(n: JsonNode) = obj(n,setOf("element","part"),"") { o -> TargetWire(s(o,"element"),nullableString(o,"part")) }
    private fun argument(n: JsonNode): ArgumentWire = when(type(n,"argument",setOf("string","long","decimal","boolean","enum","asset"))) {
        "string" -> obj(n,setOf("type","value"),"") { o -> StringArgumentWire(s(o,"value")) }; "long" -> obj(n,setOf("type","value"),"") { o -> LongArgumentWire(l(o,"value")) }; "decimal" -> obj(n,setOf("type","value"),"") { o -> DecimalArgumentWire(decimal(o,"value")) }; "boolean" -> obj(n,setOf("type","value"),"") { o -> BooleanArgumentWire(b(o,"value")) }; "enum" -> obj(n,setOf("type","value"),"") { o -> EnumArgumentWire(s(o,"value")) }; else -> obj(n,setOf("type","value"),"") { o -> AssetArgumentWire(s(o,"value")) }
    }
    private val ACTION_TYPES = setOf("start_animation","stop_animation","play_sound","set_viewer_scale","set_viewer_highlight","send_message","send_action_bar","show_title","emit_particle","application")
    private fun type(n: JsonNode, noun: String, valid: Set<String>): String { val value = obj(n, null, "") { s(it,"type") }; if(value !in valid) fail("UNKNOWN_TYPE", "Unknown $noun type."); return value }
    private fun <T> obj(n: JsonNode, fields: Set<String>?, path: String, f: (JsonNode) -> T): T { if(!n.isObject) fail("MALFORMED_JSON","Expected an object."); val names=n.properties().map { it.key }.toSet(); if(fields != null) { val unknown=names-fields; if(unknown.isNotEmpty()) fail("UNKNOWN_FIELD","Unknown field."); if(!names.containsAll(fields)) fail("MALFORMED_JSON","Required field is missing.") }; return f(n) }
    private fun a(n: JsonNode): List<JsonNode> { if(!n.isArray) fail("MALFORMED_JSON","Expected an array."); return n.toList() }
    private fun map(n: JsonNode): Map<String,JsonNode> { if(!n.isObject) fail("MALFORMED_JSON","Expected an object."); return n.properties().associate { it.key to it.value } }
    private fun s(o: JsonNode,k:String)=text(o[k]); private fun text(n:JsonNode):String { if(!n.isTextual) fail("MALFORMED_JSON","Expected a string."); return n.stringValue() }
    private fun nullableString(o:JsonNode,k:String)=if(o[k].isNull) null else text(o[k]); private fun <T> nullable(o:JsonNode,k:String,f:(JsonNode)->T)=if(o[k].isNull) null else f(o[k])
    private fun b(o:JsonNode,k:String):Boolean { val n=o[k]; if(!n.isBoolean) fail("MALFORMED_JSON","Expected a boolean."); return n.booleanValue() }
    private fun d(o:JsonNode,k:String):Double { val n=o[k]; if(!n.isNumber || !n.doubleValue().isFinite()) fail("MALFORMED_JSON","Expected a finite number."); return n.doubleValue() }
    private fun i(o:JsonNode,k:String):Int { val n=o[k]; if(!n.isIntegralNumber || !n.canConvertToInt()) fail("MALFORMED_JSON","Expected an integer."); return n.intValue() }
    private fun l(o:JsonNode,k:String):Long { val n=o[k]; if(!n.isIntegralNumber || !n.canConvertToLong()) fail("MALFORMED_JSON","Expected an integer."); return n.longValue() }
    private fun decimal(o:JsonNode,k:String):BigDecimal { val n=o[k]; if(!n.isNumber) fail("MALFORMED_JSON","Expected a number."); return n.decimalValue() }
    private fun fail(code:String,message:String):Nothing = throw DecodeFailure("/",code,message)
}
