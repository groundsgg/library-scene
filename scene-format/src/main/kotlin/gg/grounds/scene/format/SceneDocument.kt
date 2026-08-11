package gg.grounds.scene.format

import gg.grounds.scene.format.internal.immutableListCopy
import gg.grounds.scene.format.internal.immutableSetCopy

@ConsistentCopyVisibility
data class SceneMetadata
private constructor(
    val name: String,
    val description: String?,
    val tags: Set<String>,
    @Suppress("unused") private val canonical: Unit,
) {
    constructor(
        name: String,
        description: String?,
        tags: Set<String>,
    ) : this(name, description, immutableSetCopy(tags.sorted()), Unit)
}

data class SceneCatalogReferences(val assets: CatalogReference, val actions: CatalogReference)

data class SceneGroup(val id: LocalId, val displayName: String, val editorVisible: Boolean = true)

@ConsistentCopyVisibility
data class SceneDocument
private constructor(
    val schemaVersion: Int,
    val id: SceneId,
    val metadata: SceneMetadata,
    val catalogs: SceneCatalogReferences,
    val groups: List<SceneGroup>,
    val elements: List<SceneElement>,
    @Suppress("unused") private val canonical: Unit,
) {
    constructor(
        schemaVersion: Int,
        id: SceneId,
        metadata: SceneMetadata,
        catalogs: SceneCatalogReferences,
        groups: List<SceneGroup>,
        elements: List<SceneElement>,
    ) : this(
        schemaVersion,
        id,
        metadata,
        catalogs,
        immutableListCopy(groups),
        immutableListCopy(elements),
        Unit,
    )
}
