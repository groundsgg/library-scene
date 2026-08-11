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
        assertEquals(code, result.problems.single().code)
        assertEquals(path, result.problems.single().path)
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
