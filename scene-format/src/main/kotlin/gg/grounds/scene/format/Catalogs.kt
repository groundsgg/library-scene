package gg.grounds.scene.format

import gg.grounds.scene.format.internal.immutableMapCopy
import gg.grounds.scene.format.internal.immutableSetCopy
import java.math.BigDecimal

enum class AssetKind {
    PROP,
    NPC_BODY,
    SOUND,
    PARTICLE,
}

@ConsistentCopyVisibility
data class AssetDefinition
private constructor(
    val key: AssetKey,
    val kind: AssetKind,
    val animations: Set<LocalId>,
    val defaultBounds: LocalBounds?,
    val editorMetadata: Map<String, String>,
    @Suppress("unused") private val canonical: Unit,
) {
    constructor(
        key: AssetKey,
        kind: AssetKind,
        animations: Set<LocalId>,
        defaultBounds: LocalBounds?,
        editorMetadata: Map<String, String>,
    ) : this(
        key,
        kind,
        immutableSetCopy(animations),
        defaultBounds,
        immutableMapCopy(editorMetadata),
        Unit,
    )
}

data class CatalogVersionRange(
    val catalog: CatalogId,
    val minInclusive: String,
    val maxInclusive: String,
) {
    init {
        CatalogReference(catalog, minInclusive)
        CatalogReference(catalog, maxInclusive)
    }
}

@ConsistentCopyVisibility
data class AssetCatalog
private constructor(
    val id: CatalogId,
    val version: String,
    val resourcePackCompatibility: CatalogVersionRange,
    val assets: Map<AssetKey, AssetDefinition>,
    @Suppress("unused") private val canonical: Unit,
) {
    constructor(
        id: CatalogId,
        version: String,
        resourcePackCompatibility: CatalogVersionRange,
        assets: Map<AssetKey, AssetDefinition>,
    ) : this(
        id,
        CatalogReference(id, version).version,
        resourcePackCompatibility,
        immutableMapCopy(assets),
        Unit,
    )
}

enum class ActionParameterType {
    STRING,
    LONG,
    DECIMAL,
    BOOLEAN,
    ENUM,
    ASSET,
}

sealed interface ParameterConstraints

data object NoConstraints : ParameterConstraints

data class StringConstraints(val minLength: Int, val maxLength: Int, val pattern: String?) :
    ParameterConstraints {
    init {
        require(minLength >= 0 && maxLength >= minLength)
        pattern?.let(::Regex)
    }
}

data class LongConstraints(val minInclusive: Long?, val maxInclusive: Long?) :
    ParameterConstraints {
    init {
        require(minInclusive == null || maxInclusive == null || minInclusive <= maxInclusive)
    }
}

data class DecimalConstraints(val minInclusive: BigDecimal?, val maxInclusive: BigDecimal?) :
    ParameterConstraints {
    init {
        require(minInclusive == null || maxInclusive == null || minInclusive <= maxInclusive)
    }
}

@ConsistentCopyVisibility
data class EnumConstraints
private constructor(val options: Set<LocalId>, @Suppress("unused") private val canonical: Unit) :
    ParameterConstraints {
    constructor(options: Set<LocalId>) : this(immutableSetCopy(options), Unit)

    init {
        require(options.isNotEmpty())
    }
}

data class AssetConstraints(val expectedKind: AssetKind) : ParameterConstraints

data class ActionParameter(
    val id: LocalId,
    val type: ActionParameterType,
    val required: Boolean,
    val defaultValue: ApplicationArgument?,
    val constraints: ParameterConstraints,
) {
    init {
        require(defaultValue == null || argumentMatches(defaultValue, type, constraints)) {
            "Default value does not satisfy action parameter."
        }
    }
}

@ConsistentCopyVisibility
data class ActionDefinition
private constructor(
    val key: ActionKey,
    val displayName: String,
    val description: String,
    val parameters: Map<LocalId, ActionParameter>,
    @Suppress("unused") private val canonical: Unit,
) {
    constructor(
        key: ActionKey,
        displayName: String,
        description: String,
        parameters: Map<LocalId, ActionParameter>,
    ) : this(key, displayName, description, immutableMapCopy(parameters), Unit)
}

@ConsistentCopyVisibility
data class ActionCatalog
private constructor(
    val id: CatalogId,
    val version: String,
    val actions: Map<ActionKey, ActionDefinition>,
    @Suppress("unused") private val canonical: Unit,
) {
    constructor(
        id: CatalogId,
        version: String,
        actions: Map<ActionKey, ActionDefinition>,
    ) : this(id, CatalogReference(id, version).version, immutableMapCopy(actions), Unit)
}

internal fun argumentMatches(
    argument: ApplicationArgument,
    type: ActionParameterType,
    constraints: ParameterConstraints,
): Boolean =
    when (type) {
        ActionParameterType.STRING ->
            argument is StringArgument &&
                (constraints as? StringConstraints)?.let {
                    argument.value.length in it.minLength..it.maxLength &&
                        (it.pattern == null || Regex(it.pattern).matches(argument.value))
                } != false
        ActionParameterType.LONG ->
            argument is LongArgument &&
                (constraints as? LongConstraints)?.let {
                    (it.minInclusive == null || argument.value >= it.minInclusive) &&
                        (it.maxInclusive == null || argument.value <= it.maxInclusive)
                } != false
        ActionParameterType.DECIMAL ->
            argument is DecimalArgument &&
                (constraints as? DecimalConstraints)?.let {
                    (it.minInclusive == null || argument.value >= it.minInclusive) &&
                        (it.maxInclusive == null || argument.value <= it.maxInclusive)
                } != false
        ActionParameterType.BOOLEAN -> argument is BooleanArgument
        ActionParameterType.ENUM ->
            argument is EnumArgument &&
                (constraints as? EnumConstraints)?.options?.contains(argument.value) != false
        ActionParameterType.ASSET -> argument is AssetArgument
    }
