package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NpcCollisionTest {
    @Test
    fun `missing and default collision decode to defaults and encode without collision`() {
        listOf(
                npcScene(),
                sceneWithCollision("{}"),
                sceneWithCollision("""{"mode":"none"}"""),
                sceneWithCollision("""{"mode":"none","bounceStrength":6.0}"""),
            )
            .forEach { json ->
                val scene = decode(json)
                val npc = assertIs<Npc>(scene.elements.single())
                val encoded = encode(scene)

                assertEquals(NpcCollision(), npc.collision)
                assertFalse("\"collision\"" in encoded)
            }
    }

    @Test
    fun `bounce collision with authored strength round trips canonically`() {
        val scene = decode(sceneWithCollision("""{"mode":"bounce","bounceStrength":12.5}"""))
        val npc = assertIs<Npc>(scene.elements.single())
        val encoded = encode(scene)
        val decodedAgain = decode(encoded)

        assertEquals(NpcCollision(NpcCollisionMode.BOUNCE, 12.5), npc.collision)
        assertTrue(""""collision": {""" in encoded)
        assertTrue(""""mode": "bounce""" in encoded)
        assertTrue(""""bounceStrength": 12.5""" in encoded)
        assertEquals(scene, decodedAgain)
    }

    @Test
    fun `none collision retains a non-default authored strength`() {
        val scene = decode(sceneWithCollision("""{"mode":"none","bounceStrength":12.5}"""))
        val encoded = encode(scene)
        val decodedAgain = decode(encoded)

        assertTrue("\"collision\"" in encoded)
        assertEquals(
            NpcCollision(NpcCollisionMode.NONE, 12.5),
            assertIs<Npc>(decodedAgain.elements.single()).collision,
        )
    }

    @Test
    fun `collision strength accepts inclusive boundaries`() {
        listOf(0.0, 20.0).forEach { strength ->
            assertEquals(strength, NpcCollision(bounceStrength = strength).bounceStrength)
            assertEquals(
                strength,
                assertIs<Npc>(
                        decode(
                                sceneWithCollision(
                                    """{"mode":"solid","bounceStrength":$strength}"""
                                )
                            )
                            .elements
                            .single()
                    )
                    .collision
                    .bounceStrength,
            )
        }
    }

    @Test
    fun `programmatic collision rejects non-finite and out-of-range strengths`() {
        listOf(-0.1, 20.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { strength ->
                assertFailsWith<IllegalArgumentException>("strength=$strength") {
                    NpcCollision(bounceStrength = strength)
                }
            }
    }

    @Test
    fun `decode rejects invalid strengths unknown modes and unknown collision keys`() {
        listOf("-0.1", "20.1", "1e400").forEach { strength ->
            assertDecodeFailure(
                sceneWithCollision("""{"mode":"bounce","bounceStrength":$strength}"""),
                SceneProblemCode.INVALID_ACTION_ARGUMENT,
                "/elements/0/collision/bounceStrength",
            )
        }
        assertDecodeFailure(
            sceneWithCollision("""{"mode":"sticky","bounceStrength":6.0}"""),
            SceneProblemCode.UNKNOWN_TYPE,
            "/elements/0/collision/mode",
        )
        assertDecodeFailure(
            sceneWithCollision("""{"mode":"solid","bounceStrength":6.0,"extra":true}"""),
            SceneProblemCode.UNKNOWN_FIELD,
            "/elements/0/collision/extra",
        )
    }

    @Test
    fun `existing NPC constructors and first thirteen components remain present`() {
        val constructorParameterPrefixes =
            Npc::class.java.declaredConstructors.map { constructor ->
                constructor.parameterTypes.take(13).map(Class<*>::getName)
            }
        val oldParameters =
            listOf(
                "java.lang.String",
                "java.lang.String",
                Transform::class.java.name,
                "boolean",
                ActivationPolicy::class.java.name,
                "java.lang.String",
                "net.kyori.adventure.text.Component",
                Vec3::class.java.name,
                LookBehavior::class.java.name,
                "java.lang.String",
                LocalBounds::class.java.name,
                ProximitySensor::class.java.name,
                "java.util.List",
            )

        assertTrue(constructorParameterPrefixes.count { it == oldParameters } >= 2)
        assertEquals(
            (1..13).map { "component$it" },
            Npc::class
                .java
                .declaredMethods
                .map { it.name.substringBefore('-') }
                .filter { it.matches(Regex("component(?:[1-9]|1[0-3])")) }
                .distinct()
                .sortedBy { it.removePrefix("component").toInt() },
        )
    }

    private fun decode(json: String): SceneDocument =
        SceneJson.decode(json.encodeToByteArray()).let { result ->
            assertIs<SceneDecodeResult.Success>(result, result.toString()).scene
        }

    private fun encode(scene: SceneDocument): String =
        assertIs<SceneEncodeResult.Success>(SceneJson.encode(scene)).bytes.decodeToString()

    private fun assertDecodeFailure(json: String, code: SceneProblemCode, path: String) {
        val result = assertIs<SceneDecodeResult.Failure>(SceneJson.decode(json.encodeToByteArray()))
        assertEquals(code, result.problems.single().code)
        assertEquals(path, result.problems.single().path)
    }

    private fun sceneWithCollision(collision: String): String =
        npcScene().replace("\"bindings\":", "\"collision\":$collision,\"bindings\":")
}
