package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NpcCollisionTest {
    @Test
    fun `fractional block distance round trips without a redundant legacy speed`() {
        listOf("0.5", "1.5", "2.25", "0", "20").forEach { distance ->
            val scene =
                decode(sceneWithCollision("""{"mode":"bounce","bounceDistance":$distance}"""))
            val encoded = encode(scene)
            assertTrue("\"bounceDistance\"" in encoded)
            assertFalse("\"bounceStrength\"" in encoded)
            assertEquals(scene, decode(encoded))
        }
    }

    @Test
    fun `distance rejects invalid raw decimals and ambiguous legacy speed`() {
        listOf("-1e-400", "20.0000000000000001", "1e400").forEach { distance ->
            assertDecodeFailure(
                sceneWithCollision("""{"mode":"bounce","bounceDistance":$distance}"""),
                SceneProblemCode.INVALID_ACTION_ARGUMENT,
                "/elements/0/collision/bounceDistance",
            )
        }
        assertDecodeFailure(
            sceneWithCollision("""{"mode":"bounce","bounceDistance":0.5,"bounceStrength":6}"""),
            SceneProblemCode.INVALID_ACTION_ARGUMENT,
            "/elements/0/collision",
        )
    }

    @Test
    fun `legacy collision constructor and copy JVM descriptors remain callable`() {
        val type = NpcCollision::class.java
        val mode = NpcCollisionMode::class.java
        val double = Double::class.javaPrimitiveType!!
        val mask = Int::class.javaPrimitiveType!!
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        type.getDeclaredConstructor(mode, double)
        type.getDeclaredConstructor(mode, double, mask, marker)
        val old = NpcCollision(NpcCollisionMode.NONE, 12.5)
        val copied =
            type.getDeclaredMethod("copy", mode, double).invoke(old, NpcCollisionMode.BOUNCE, 12.5)
        assertEquals(NpcCollision(NpcCollisionMode.BOUNCE, 12.5), copied)
        val defaultCopy =
            type
                .getDeclaredMethod("copy\$default", type, mode, double, mask, Any::class.java)
                .invoke(null, old, NpcCollisionMode.BOUNCE, 0.0, 2, null)
        assertEquals(NpcCollision(NpcCollisionMode.BOUNCE, 12.5), defaultCopy)
        type.getDeclaredConstructor()
    }

    @Test
    fun `distance survives mode copies and explicit legacy construction remains speed based`() {
        val distance = NpcCollision(NpcCollisionMode.BOUNCE, 6.0, 0.5)
        assertEquals(0.5, distance.copy(mode = NpcCollisionMode.SOLID).bounceDistance)
        assertEquals(1.5, distance.copy(bounceDistance = 1.5).bounceDistance)
        assertEquals(null, distance.copy(bounceStrength = 12.5).bounceDistance)
        assertEquals(null, NpcCollision(distance.mode, 6.0).bounceDistance)
        assertEquals(6.0, NpcCollision(distance.mode, 6.0).bounceStrength)
    }

    @Test
    fun `programmatic distance rejects non-finite out-of-range and conflicting speed`() {
        listOf(-0.1, 20.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { distance ->
                assertFailsWith<IllegalArgumentException> {
                    NpcCollision(NpcCollisionMode.BOUNCE, 6.0, distance)
                }
            }
        assertFailsWith<IllegalArgumentException> {
            NpcCollision(NpcCollisionMode.BOUNCE, 12.0, 0.5)
        }
    }

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
    fun `decode rejects strength above maximum before rounding to double`() {
        assertDecodeFailure(
            sceneWithCollision("""{"mode":"bounce","bounceStrength":20.0000000000000001}"""),
            SceneProblemCode.INVALID_ACTION_ARGUMENT,
            "/elements/0/collision/bounceStrength",
        )
    }

    @Test
    fun `decode rejects negative strength before underflowing to double zero`() {
        assertDecodeFailure(
            sceneWithCollision("""{"mode":"bounce","bounceStrength":-1e-400}"""),
            SceneProblemCode.INVALID_ACTION_ARGUMENT,
            "/elements/0/collision/bounceStrength",
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
