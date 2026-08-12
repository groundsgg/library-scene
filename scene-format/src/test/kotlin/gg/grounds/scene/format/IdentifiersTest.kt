package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdentifiersTest {
    @Test
    fun `namespaced identifiers accept boundaries and reject bad segments`() {
        assertEquals("grounds:lobby/npc_1", SceneId("grounds:lobby/npc_1").value)
        assertEquals("a:z", AssetKey("a:z").value)
        assertEquals("a:z", ActionKey("a:z").value)
        assertEquals("a:z", CatalogId("a:z").value)
        assertEquals("npc_1", LocalId("npc_1").value)
        assertFailsWith<IllegalArgumentException> { SceneId("Grounds:lobby") }
        assertFailsWith<IllegalArgumentException> { AssetKey("grounds:a//b") }
        assertFailsWith<IllegalArgumentException> { ActionKey("grounds:a/../b") }
        assertFailsWith<IllegalArgumentException> { CatalogId("grounds:") }
        assertFailsWith<IllegalArgumentException> { LocalId("a/b") }
    }

    @Test
    fun `catalog reference rejects non-printable or whitespace versions`() {
        assertEquals("1.0.0", CatalogReference(CatalogId("grounds:catalog"), "1.0.0").version)
        assertFailsWith<IllegalArgumentException> {
            CatalogReference(CatalogId("grounds:catalog"), "")
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogReference(CatalogId("grounds:catalog"), "1 0")
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogReference(CatalogId("grounds:catalog"), "1\n0")
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogReference(CatalogId("grounds:catalog"), "1\u007f")
        }
    }
}
