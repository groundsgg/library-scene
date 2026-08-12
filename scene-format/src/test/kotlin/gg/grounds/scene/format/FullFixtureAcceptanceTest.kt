package gg.grounds.scene.format

import gg.grounds.scene.testkit.SceneFixtures
import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FullFixtureAcceptanceTest {
    @Test
    fun `complete fixture validates encodes canonically decodes and preserves exact semantics`() {
        val scene = SceneFixtures.complete()
        assertTrue(SceneValidation.validateIntrinsic(scene).isValid)
        assertTrue(SceneValidation.validateCatalogs(scene, assetCatalog(), actionCatalog()).isValid)

        val first = assertIs<SceneEncodeResult.Success>(SceneJson.encode(scene)).bytes
        val second = assertIs<SceneEncodeResult.Success>(SceneJson.encode(scene)).bytes
        assertContentEquals(first, second)
        assertContentEquals(sha256(first), sha256(second))

        val decoded = assertIs<SceneDecodeResult.Success>(SceneJson.decode(first)).scene
        assertTrue(SceneValidation.validateIntrinsic(decoded).isValid)
        assertTrue(
            SceneValidation.validateCatalogs(decoded, assetCatalog(), actionCatalog()).isValid
        )
        assertEquals(scene, decoded)
    }

    private fun assetCatalog() =
        AssetCatalog(
            CatalogId("test:assets"),
            "1",
            CatalogVersionRange(CatalogId("test:pack"), "1", "1"),
            mapOf(
                AssetKey("test:prop") to
                    AssetDefinition(
                        AssetKey("test:prop"),
                        AssetKind.PROP,
                        setOf(LocalId("idle"), LocalId("wave")),
                        null,
                        emptyMap(),
                    ),
                AssetKey("test:part") to
                    AssetDefinition(
                        AssetKey("test:part"),
                        AssetKind.PROP,
                        emptySet(),
                        null,
                        emptyMap(),
                    ),
                AssetKey("test:npc") to
                    AssetDefinition(
                        AssetKey("test:npc"),
                        AssetKind.NPC_BODY,
                        setOf(LocalId("idle")),
                        null,
                        emptyMap(),
                    ),
                AssetKey("test:sound") to
                    AssetDefinition(
                        AssetKey("test:sound"),
                        AssetKind.SOUND,
                        emptySet(),
                        null,
                        emptyMap(),
                    ),
                AssetKey("test:particle") to
                    AssetDefinition(
                        AssetKey("test:particle"),
                        AssetKind.PARTICLE,
                        emptySet(),
                        null,
                        emptyMap(),
                    ),
                AssetKey("test:asset") to
                    AssetDefinition(
                        AssetKey("test:asset"),
                        AssetKind.PROP,
                        emptySet(),
                        null,
                        emptyMap(),
                    ),
            ),
        )

    private fun actionCatalog() =
        ActionCatalog(
            CatalogId("test:actions"),
            "1",
            mapOf(
                ActionKey("test:application") to
                    ActionDefinition(
                        "test:application".let(::ActionKey),
                        "Application",
                        "",
                        mapOf(
                            LocalId("string") to
                                ActionParameter(
                                    LocalId("string"),
                                    ActionParameterType.STRING,
                                    true,
                                    null,
                                    StringConstraints(0, 32, null),
                                ),
                            LocalId("long") to
                                ActionParameter(
                                    LocalId("long"),
                                    ActionParameterType.LONG,
                                    true,
                                    null,
                                    LongConstraints(0, 100),
                                ),
                            LocalId("decimal") to
                                ActionParameter(
                                    LocalId("decimal"),
                                    ActionParameterType.DECIMAL,
                                    true,
                                    null,
                                    DecimalConstraints(BigDecimal.ZERO, BigDecimal("20")),
                                ),
                            LocalId("boolean") to
                                ActionParameter(
                                    LocalId("boolean"),
                                    ActionParameterType.BOOLEAN,
                                    true,
                                    null,
                                    NoConstraints,
                                ),
                            LocalId("enum") to
                                ActionParameter(
                                    LocalId("enum"),
                                    ActionParameterType.ENUM,
                                    true,
                                    null,
                                    EnumConstraints(setOf(LocalId("choice"))),
                                ),
                            LocalId("asset") to
                                ActionParameter(
                                    LocalId("asset"),
                                    ActionParameterType.ASSET,
                                    true,
                                    null,
                                    AssetConstraints(AssetKind.PROP),
                                ),
                        ),
                    )
            ),
        )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
