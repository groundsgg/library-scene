package gg.grounds.scene.format

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class ReleasePleaseBootstrapTest {
    @Test
    fun `configuration bootstraps first release as exactly 0_1_0`() {
        val root = Path.of(System.getProperty("sceneRepositoryRoot"))
        val mapper = JsonMapper.builder().build()
        val config = mapper.readTree(root.resolve("release-please-config.json").toFile())

        assertEquals("java", config["release-type"].stringValue())
        assertFalse(config["include-component-in-tag"].booleanValue())
        assertEquals("0.1.0", config["initial-version"].stringValue())
        assertEquals(null, config["release-as"])
        assertEquals(setOf("."), config["packages"].properties().map { it.key }.toSet())
    }

    @Test
    fun `manifest tracks a stable semantic version throughout the release lifecycle`() {
        val root = Path.of(System.getProperty("sceneRepositoryRoot"))
        val mapper = JsonMapper.builder().build()
        val manifest = mapper.readTree(root.resolve(".release-please-manifest.json").toFile())

        assertEquals(setOf("."), manifest.properties().map { it.key }.toSet())
        assertTrue(
            STABLE_SEMANTIC_VERSION.matches(manifest["."].stringValue()),
            "Release Please manifest version must be a stable semantic version",
        )
    }

    private companion object {
        val STABLE_SEMANTIC_VERSION = Regex("""(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)""")
    }
}
