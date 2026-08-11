package gg.grounds.scene.format

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import tools.jackson.databind.json.JsonMapper

class ReleasePleaseBootstrapTest {
    @Test
    fun `manifest bootstrap proposes first feature release as exactly 0_1_0`() {
        val root = Path.of(System.getProperty("sceneRepositoryRoot"))
        val mapper = JsonMapper.builder().build()
        val config = mapper.readTree(root.resolve("release-please-config.json").toFile())
        val manifest = mapper.readTree(root.resolve(".release-please-manifest.json").toFile())

        assertEquals("java", config["release-type"].stringValue())
        assertFalse(config["include-component-in-tag"].booleanValue())
        assertEquals("0.1.0", config["initial-version"].stringValue())
        assertEquals(null, config["release-as"])
        assertEquals(setOf("."), config["packages"].properties().map { it.key }.toSet())
        assertEquals(setOf("."), manifest.properties().map { it.key }.toSet())
        assertEquals("0.0.0", manifest["."].stringValue())
    }
}
