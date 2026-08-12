package gg.grounds.scene.format

private val identifierSegment = Regex("[a-z0-9._-]+")

@JvmInline
value class SceneId(val value: String) {
    init {
        requireNamespaced(value)
    }
}

@JvmInline
value class AssetKey(val value: String) {
    init {
        requireNamespaced(value)
    }
}

@JvmInline
value class ActionKey(val value: String) {
    init {
        requireNamespaced(value)
    }
}

@JvmInline
value class CatalogId(val value: String) {
    init {
        requireNamespaced(value)
    }
}

@JvmInline
value class LocalId(val value: String) {
    init {
        requireLocal(value)
    }
}

data class CatalogReference(val id: CatalogId, val version: String) {
    init {
        require(version.isNotEmpty() && version.all { it.code in 0x21..0x7e }) {
            "Catalog version must contain printable ASCII characters without whitespace."
        }
    }
}

private fun requireNamespaced(value: String) {
    val parts = value.split(':')
    require(parts.size == 2 && identifierSegment.matches(parts[0])) {
        "Identifier must have a lowercase namespace."
    }
    require(parts[1].split('/').all { it != "." && it != ".." && identifierSegment.matches(it) }) {
        "Identifier path must contain non-empty lowercase segments."
    }
}

private fun requireLocal(value: String) {
    require(identifierSegment.matches(value)) { "Local identifier must be a lowercase segment." }
}
