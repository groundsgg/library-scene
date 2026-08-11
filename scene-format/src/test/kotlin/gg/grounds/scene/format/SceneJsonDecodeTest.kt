package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SceneJsonDecodeTest {
    @Test
    fun `decode classifies strict parser failures without exception-message matching`() {
        assertFailure(
            validJson()
                .replace("\"id\":\"test:scene\"", "\"id\":\"test:scene\",\"id\":\"test:other\""),
            SceneProblemCode.DUPLICATE_FIELD,
            "/id",
        )
        assertFailure(validJson() + "{}", SceneProblemCode.TRAILING_TOKEN, "/")
        assertFailure(validJson().dropLast(1), SceneProblemCode.MALFORMED_JSON, "/")
        assertFailure(
            validJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            SceneProblemCode.UNSUPPORTED_SCHEMA_VERSION,
            "/schemaVersion",
        )
    }

    @Test
    fun `decode reports exact JSON pointers for wire failures`() {
        assertFailure(
            validJson().replaceFirst("\"x\":0.0", "\"x\":0.0,\"rogue\":0"),
            SceneProblemCode.UNKNOWN_FIELD,
            "/elements/0/transform/position/rogue",
        )
        assertFailure(
            validJson().replace(",\"asset\":\"test:prop\"", ""),
            SceneProblemCode.MALFORMED_JSON,
            "/elements/0/asset",
        )
        assertFailure(
            validJson().replace("\"visible\":true", "\"visible\":\"true\""),
            SceneProblemCode.MALFORMED_JSON,
            "/elements/0/visible",
        )
        assertFailure(
            validJson().replace("\"type\":\"prop\"", "\"type\":\"java.lang.Runtime\""),
            SceneProblemCode.UNKNOWN_TYPE,
            "/elements/0/type",
        )
        assertFailure(
            validJson().replace("\"type\":\"prop\",", ""),
            SceneProblemCode.MALFORMED_JSON,
            "/elements/0/type",
        )
    }

    @Test
    fun `unknown enum values have a stable semantic code at the exact field`() {
        assertFailure(
            validJson().replace("\"activation\":\"AUTOMATIC\"", "\"activation\":\"LATER\""),
            SceneProblemCode.UNKNOWN_TYPE,
            "/elements/0/activation",
        )
        assertFailure(
            npcScene().replace("\"trigger\":\"RIGHT_CLICK\"", "\"trigger\":\"WHENEVER\""),
            SceneProblemCode.UNKNOWN_TYPE,
            "/elements/0/bindings/0/trigger",
        )
        assertFailure(
            npcScene(conditions = """[{"type":"hand","hand":"BOTH"}]"""),
            SceneProblemCode.UNKNOWN_TYPE,
            "/elements/0/bindings/0/conditions/0/hand",
        )
        assertFailure(
            npcScene(conditions = """[{"type":"game_mode","gameMode":"BUILDER"}]"""),
            SceneProblemCode.UNKNOWN_TYPE,
            "/elements/0/bindings/0/conditions/0/gameMode",
        )
    }

    @Test
    fun `embedded components stay inside strict Jackson parsing and explicit validation`() {
        val actionPath = "/elements/0/bindings/0/actions/0/message"
        assertFailure(
            npcScene(action = """{"type":"send_message","message":{"text":"one","text":"two"}}"""),
            SceneProblemCode.DUPLICATE_FIELD,
            "$actionPath/text",
        )
        assertFailure(
            npcScene(
                action = """{"type":"send_message","message":{"text":"hello","rogue":true}}"""
            ),
            SceneProblemCode.UNKNOWN_FIELD,
            "$actionPath/rogue",
        )
        assertFailure(
            npcScene(action = """{"type":"send_message","message":{"color":"red"}}"""),
            SceneProblemCode.UNKNOWN_TYPE,
            actionPath,
        )

        listOf(
                """{"text":"click","click_event":{"action":"run_command","command":"/say hi"}}""",
                """{"text":"hover","hover_event":{"action":"show_text","contents":{"text":"details"}}}""",
                """{"text":"insert","insertion":"unsafe"}""",
            )
            .forEach { component ->
                val result =
                    SceneJson.decode(
                        npcScene(action = """{"type":"send_message","message":$component}""")
                            .encodeToByteArray()
                    )
                assertIs<SceneDecodeResult.Failure>(result)
                assertEquals(SceneProblemCode.FORBIDDEN_TEXT_EVENT, result.problems.single().code)
            }
    }

    @Test
    fun `unsupported Adventure click fields and unreadable actions are rejected at exact pointers`() {
        val eventPath = "/elements/0/bindings/0/actions/0/message/click_event"
        listOf("id", "payload").forEach { field ->
            assertFailure(
                npcScene(
                    action =
                        """{"type":"send_message","message":{"text":"unsafe","click_event":{"action":"run_command","$field":"ignored"}}}"""
                ),
                SceneProblemCode.UNKNOWN_FIELD,
                "$eventPath/$field",
            )
        }
        listOf("open_file", "future_action").forEach { action ->
            assertFailure(
                npcScene(
                    action =
                        """{"type":"send_message","message":{"text":"unsafe","click_event":{"action":"$action","path":"/tmp/file"}}}"""
                ),
                SceneProblemCode.UNKNOWN_TYPE,
                "$eventPath/action",
            )
        }
    }

    @Test
    fun `every accepted Adventure click shape materializes for intrinsic rejection`() {
        listOf(
                """{"action":"copy_to_clipboard","value":"copy"}""",
                """{"action":"open_url","url":"https://example.com"}""",
                """{"action":"run_command","path":"/recognized-by-gson"}""",
                """{"action":"suggest_command","command":"/say hi"}""",
                """{"action":"change_page","page":2}""",
            )
            .forEach { event ->
                val result =
                    SceneJson.decode(
                        npcScene(
                                action =
                                    """{"type":"send_message","message":{"text":"unsafe","click_event":$event}}"""
                            )
                            .encodeToByteArray()
                    )
                assertIs<SceneDecodeResult.Failure>(result)
                assertEquals(SceneProblemCode.FORBIDDEN_TEXT_EVENT, result.problems.single().code)
            }
    }

    @Test
    fun `Adventure four-number shadow color array decodes as safe rich text`() {
        val result =
            SceneJson.decode(
                npcScene(
                        action =
                            """{"type":"send_message","message":{"text":"shadowed","shadow_color":[1.0,0.5,0.25,0.75]}}"""
                    )
                    .encodeToByteArray()
            )
        assertIs<SceneDecodeResult.Success>(result)
    }

    @Test
    fun `Adventure packed shadow color follows Gson integer-valued numeric coercion`() {
        assertIs<SceneDecodeResult.Success>(
            SceneJson.decode(sceneWithPackedShadowColor("1.0").encodeToByteArray())
        )
        assertIs<SceneDecodeResult.Success>(
            SceneJson.decode(sceneWithPackedShadowColor("1e0").encodeToByteArray())
        )
        assertFailure(
            sceneWithPackedShadowColor("1.5"),
            SceneProblemCode.MALFORMED_JSON,
            "/elements/0/bindings/0/actions/0/message/shadow_color",
        )
        assertFailure(
            sceneWithPackedShadowColor("2147483648"),
            SceneProblemCode.MALFORMED_JSON,
            "/elements/0/bindings/0/actions/0/message/shadow_color",
        )
    }

    @Test
    fun `decode maps the complete v1 wire model and passes intrinsic validation`() {
        val result = SceneJson.decode(completeJson().encodeToByteArray())
        assertIs<SceneDecodeResult.Success>(result)
        val scene = result.scene
        assertEquals(4, scene.elements.size)
        assertIs<Prop>(scene.elements[0])
        assertIs<CompositeProp>(scene.elements[1])
        assertIs<LookBehavior.Fixed>(assertIs<Npc>(scene.elements[2]).look)
        val npc = assertIs<Npc>(scene.elements[3])
        assertIs<LookBehavior.TrackNearest>(npc.look)
        assertEquals(
            listOf(
                HandCondition::class,
                SneakingCondition::class,
                PermissionCondition::class,
                GameModeCondition::class,
            ),
            npc.bindings.single().conditions.map { it::class },
        )
        assertEquals(
            listOf(
                StartAnimationAction::class,
                StopAnimationAction::class,
                PlaySoundAction::class,
                SetViewerScaleAction::class,
                SetViewerHighlightAction::class,
                SendMessageAction::class,
                SendActionBarAction::class,
                ShowTitleAction::class,
                EmitParticleAction::class,
                ApplicationAction::class,
            ),
            npc.bindings.single().actions.map { it::class },
        )
        val application = assertIs<ApplicationAction>(npc.bindings.single().actions.last())
        assertEquals(
            listOf(
                StringArgument::class,
                LongArgument::class,
                DecimalArgument::class,
                BooleanArgument::class,
                EnumArgument::class,
                AssetArgument::class,
            ),
            application.arguments.values.map { it::class },
        )
        assertTrue(SceneValidation.validateIntrinsic(scene).problems.isEmpty())
    }

    @Test
    fun `decimal arguments reject oversized canonical expansion at their exact pointer`() {
        val path = "/elements/3/bindings/0/actions/9/arguments/decimal/value"

        assertFailure(
            completeJson().replace("\"value\":12.50", "\"value\":1e200"),
            SceneProblemCode.LIMIT_EXCEEDED,
            path,
        )
        assertFailure(
            completeJson().replace("\"value\":12.50", "\"value\":1e1000000"),
            SceneProblemCode.LIMIT_EXCEEDED,
            path,
        )
    }

    @Test
    fun `domain identifier and version invariants retain their exact wire pointer`() {
        val applicationPath = "/elements/3/bindings/0/actions/9"
        listOf(
                Triple(
                    validJson().replace("\"id\":\"test:scene\"", "\"id\":\"Test:scene\""),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "/id",
                ),
                Triple(
                    validJson().replace("\"id\":\"test:assets\"", "\"id\":\"Test:assets\""),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "/catalogs/assets/id",
                ),
                Triple(
                    validJson()
                        .replace(
                            "\"id\":\"test:assets\",\"version\":\"1\"",
                            "\"id\":\"test:assets\",\"version\":\"bad version\"",
                        ),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "/catalogs/assets/version",
                ),
                Triple(
                    completeJson().replace("\"id\": \"actors\"", "\"id\": \"bad/id\""),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "/groups/0/id",
                ),
                Triple(
                    validJson().replace("\"id\":\"prop\"", "\"id\":\"bad/id\""),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "/elements/0/id",
                ),
                Triple(
                    completeJson().replaceFirst("\"group\":\"actors\"", "\"group\":\"bad/id\""),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "/elements/2/group",
                ),
                Triple(
                    validJson().replace("\"asset\":\"test:prop\"", "\"asset\":\"Test:prop\""),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "/elements/0/asset",
                ),
                Triple(
                    completeJson()
                        .replace("\"key\":\"test:application\"", "\"key\":\"Test:application\""),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "$applicationPath/key",
                ),
                Triple(
                    completeJson()
                        .replaceFirst(
                            "\"target\":{\"element\":\"prop\"",
                            "\"target\":{\"element\":\"bad/id\"",
                        ),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "/elements/3/bindings/0/actions/0/target/element",
                ),
                Triple(
                    completeJson()
                        .replace(
                            "\"enum\":{\"type\":\"enum\",\"value\":\"choice\"}",
                            "\"bad/id\":{\"type\":\"enum\",\"value\":\"choice\"}",
                        ),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "$applicationPath/arguments/bad~1id",
                ),
                Triple(
                    completeJson().replace("\"value\":\"choice\"", "\"value\":\"bad/id\""),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "$applicationPath/arguments/enum/value",
                ),
                Triple(
                    completeJson().replace("\"value\":\"test:asset\"", "\"value\":\"Test:asset\""),
                    SceneProblemCode.INVALID_IDENTIFIER,
                    "$applicationPath/arguments/asset/value",
                ),
            )
            .forEach { (json, code, path) -> assertFailure(json, code, path) }
    }

    @Test
    fun `domain geometry interaction and action invariants retain their exact wire pointer`() {
        val bindingPath = "/elements/0/bindings/0"
        val actionPath = "/elements/3/bindings/0/actions"
        listOf(
                Triple(
                    validJson().replaceFirst("\"x\":0.0", "\"x\":1e400"),
                    SceneProblemCode.NON_FINITE_TRANSFORM,
                    "/elements/0/transform/position/x",
                ),
                Triple(
                    validJson().replace("\"yaw\":0.0", "\"yaw\":1e400"),
                    SceneProblemCode.NON_FINITE_TRANSFORM,
                    "/elements/0/transform/rotation/yaw",
                ),
                Triple(
                    validJson().replace("\"scale\":{\"x\":1.0", "\"scale\":{\"x\":0.0"),
                    SceneProblemCode.INVALID_SCALE,
                    "/elements/0/transform/scale/x",
                ),
                Triple(
                    npcScene().replace("\"size\":{\"x\":1.0", "\"size\":{\"x\":0.0"),
                    SceneProblemCode.INVALID_BOUNDS,
                    "/elements/0/interactionBounds/size/x",
                ),
                Triple(
                    completeJson().replace("\"maxDistance\":12.0", "\"maxDistance\":0.0"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "/elements/3/look/maxDistance",
                ),
                Triple(
                    completeJson()
                        .replace(
                            "\"maxTurnDegreesPerSecond\":90.0",
                            "\"maxTurnDegreesPerSecond\":0.0",
                        ),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "/elements/3/look/maxTurnDegreesPerSecond",
                ),
                Triple(
                    completeJson().replace("\"enterRadius\":3.0", "\"enterRadius\":0.0"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "/elements/3/proximity/enterRadius",
                ),
                Triple(
                    completeJson().replace("\"exitRadius\":4.0", "\"exitRadius\":3.0"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "/elements/3/proximity/exitRadius",
                ),
                Triple(
                    npcScene(conditions = """[{"type":"permission","permission":"bad value"}]"""),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$bindingPath/conditions/0/permission",
                ),
                Triple(
                    npcScene().replace("\"cooldownMillis\":0", "\"cooldownMillis\":-1"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$bindingPath/cooldownMillis",
                ),
                Triple(
                    npcScene().replace("\"debounceMillis\":0", "\"debounceMillis\":-1"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$bindingPath/debounceMillis",
                ),
                Triple(
                    npcScene()
                        .replace(
                            "\"actions\":[{\"type\":\"send_message\",\"message\":{\"text\":\"hello\"}}]",
                            "\"actions\":[]",
                        ),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$bindingPath/actions",
                ),
                Triple(
                    completeJson().replace("\"volume\":1.0", "\"volume\":0.0"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/2/volume",
                ),
                Triple(
                    completeJson().replace("\"pitch\":1.0", "\"pitch\":0.0"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/2/pitch",
                ),
                Triple(
                    completeJson().replace("\"multiplier\":1.25", "\"multiplier\":0.0"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/3/multiplier",
                ),
                Triple(
                    completeJson().replace("\"transitionMillis\":250", "\"transitionMillis\":-1"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/3/transitionMillis",
                ),
                Triple(
                    completeJson()
                        .replace(
                            "\"enabled\":true,\"transitionMillis\":100",
                            "\"enabled\":true,\"transitionMillis\":-1",
                        ),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/4/transitionMillis",
                ),
                Triple(
                    completeJson().replace("\"fadeInMillis\":100", "\"fadeInMillis\":-1"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/7/fadeInMillis",
                ),
                Triple(
                    completeJson().replace("\"stayMillis\":1000", "\"stayMillis\":-1"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/7/stayMillis",
                ),
                Triple(
                    completeJson().replace("\"fadeOutMillis\":100", "\"fadeOutMillis\":-1"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/7/fadeOutMillis",
                ),
                Triple(
                    completeJson().replace("\"count\":4", "\"count\":-1"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/8/count",
                ),
                Triple(
                    completeJson().replace("\"speed\":0.5", "\"speed\":-1.0"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/8/speed",
                ),
                Triple(
                    completeJson().replace("\"x\":0.1,\"y\":0.2", "\"x\":1e400,\"y\":0.2"),
                    SceneProblemCode.INVALID_ACTION_ARGUMENT,
                    "$actionPath/8/offset/x",
                ),
            )
            .forEach { (json, code, path) -> assertFailure(json, code, path) }
    }

    @Test
    fun `decode never returns a partial scene or a Jackson exception`() {
        val result: SceneDecodeResult = SceneJson.decode(byteArrayOf(0xC3.toByte(), 0x28))
        assertIs<SceneDecodeResult.Failure>(result)
        assertTrue(result.problems.isNotEmpty())
    }

    private fun assertFailure(json: String, code: SceneProblemCode, path: String) =
        assertFailure(json.encodeToByteArray(), code, path)

    private fun sceneWithPackedShadowColor(value: String) =
        npcScene(
            action =
                """{"type":"send_message","message":{"text":"shadowed","shadow_color":$value}}"""
        )

    private fun assertFailure(bytes: ByteArray, code: SceneProblemCode, path: String) {
        val result = SceneJson.decode(bytes)
        assertIs<SceneDecodeResult.Failure>(result)
        assertEquals(
            code,
            result.problems.single().code,
            "problem code at $path: ${result.problems}",
        )
        assertEquals(path, result.problems.single().path, "problem pointer for $code")
    }
}

internal fun validJson() =
    """{"schemaVersion":1,"id":"test:scene","metadata":{"name":"Scene","description":null,"tags":[]},"catalogs":{"assets":{"id":"test:assets","version":"1"},"actions":{"id":"test:actions","version":"1"}},"groups":[],"elements":[{"type":"prop","id":"prop","group":null,"transform":${transformJson()},"visible":true,"activation":"AUTOMATIC","asset":"test:prop","initialAnimation":null}]}"""

internal fun npcScene(
    action: String = """{"type":"send_message","message":{"text":"hello"}}""",
    conditions: String = "[]",
) =
    """{"schemaVersion":1,"id":"test:scene","metadata":{"name":"Scene","description":null,"tags":[]},"catalogs":{"assets":{"id":"test:assets","version":"1"},"actions":{"id":"test:actions","version":"1"}},"groups":[],"elements":[{"type":"npc","id":"npc","group":null,"transform":${transformJson()},"visible":true,"activation":"AUTOMATIC","body":"test:npc","label":{"text":"Guide"},"labelOffset":{"x":0.0,"y":1.0,"z":0.0},"look":{"type":"fixed"},"initialAnimation":null,"interactionBounds":{"center":{"x":0.0,"y":1.0,"z":0.0},"size":{"x":1.0,"y":2.0,"z":1.0}},"proximity":null,"bindings":[{"trigger":"RIGHT_CLICK","conditions":$conditions,"cooldownMillis":0,"debounceMillis":0,"actions":[$action]}]}]}"""

internal fun completeJson() =
    """
    {
      "schemaVersion": 1,
      "id": "test:complete",
      "metadata": {"name": "Complete", "description": "all variants", "tags": ["fixture", "v1"]},
      "catalogs": {
        "assets": {"id": "test:assets", "version": "1"},
        "actions": {"id": "test:actions", "version": "1"}
      },
      "groups": [{"id": "actors", "displayName": "Actors", "editorVisible": true}],
      "elements": [
        {"type":"prop","id":"prop","group":null,"transform":${transformJson()},"visible":true,"activation":"AUTOMATIC","asset":"test:prop","initialAnimation":"idle"},
        {"type":"composite_prop","id":"composite","group":null,"transform":${transformJson()},"visible":true,"activation":"ALWAYS","parts":[{"id":"part","asset":"test:part","transform":${transformJson()}}]},
        {"type":"npc","id":"fixed","group":"actors","transform":${transformJson()},"visible":true,"activation":"AUTOMATIC","body":"test:npc","label":null,"labelOffset":{"x":0,"y":1,"z":0},"look":{"type":"fixed"},"initialAnimation":null,"interactionBounds":{"center":{"x":0,"y":1,"z":0},"size":{"x":1,"y":2,"z":1}},"proximity":null,"bindings":[]},
        {"type":"npc","id":"tracked","group":"actors","transform":${transformJson()},"visible":true,"activation":"ALWAYS","body":"test:npc","label":{"text":"Guide","extra":[{"translate":"scene.greeting","fallback":"Hello","with":[{"text":"player"}]}]},"labelOffset":{"x":0,"y":1,"z":0},"look":{"type":"track_nearest","maxDistance":12.0,"yawOnly":true,"maxTurnDegreesPerSecond":90.0},"initialAnimation":"idle","interactionBounds":{"center":{"x":0,"y":1,"z":0},"size":{"x":1,"y":2,"z":1}},"proximity":{"enterRadius":3.0,"exitRadius":4.0},"bindings":[{
          "trigger":"RIGHT_CLICK",
          "conditions":[{"type":"hand","hand":"MAIN"},{"type":"sneaking","sneaking":false},{"type":"permission","permission":"scene.use"},{"type":"game_mode","gameMode":"ADVENTURE"}],
          "cooldownMillis":100,
          "debounceMillis":10,
          "actions":[
            {"type":"start_animation","target":{"element":"prop","part":null},"animation":"wave"},
            {"type":"stop_animation","target":{"element":"composite","part":"part"},"animation":null},
            {"type":"play_sound","sound":"test:sound","volume":1.0,"pitch":1.0},
            {"type":"set_viewer_scale","target":{"element":"tracked","part":null},"multiplier":1.25,"transitionMillis":250},
            {"type":"set_viewer_highlight","target":{"element":"tracked","part":null},"enabled":true,"transitionMillis":100},
            {"type":"send_message","message":{"text":"Message"}},
            {"type":"send_action_bar","message":"Action bar"},
            {"type":"show_title","title":{"text":"Title"},"subtitle":{"text":"Subtitle"},"fadeInMillis":100,"stayMillis":1000,"fadeOutMillis":100},
            {"type":"emit_particle","target":{"element":"composite","part":"part"},"particle":"test:particle","count":4,"offset":{"x":0.1,"y":0.2,"z":0.3},"speed":0.5},
            {"type":"application","key":"test:application","arguments":{
              "string":{"type":"string","value":"value"},
              "long":{"type":"long","value":42},
              "decimal":{"type":"decimal","value":12.50},
              "boolean":{"type":"boolean","value":true},
              "enum":{"type":"enum","value":"choice"},
              "asset":{"type":"asset","value":"test:asset"}
            }}
          ]
        }]}
      ]
    }
"""
        .trimIndent()

internal fun transformJson() =
    """{"position":{"x":0.0,"y":0.0,"z":0.0},"rotation":{"yaw":0.0,"pitch":0.0,"roll":0.0},"scale":{"x":1.0,"y":1.0,"z":1.0}}"""
