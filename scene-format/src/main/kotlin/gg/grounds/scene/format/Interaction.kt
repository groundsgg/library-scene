package gg.grounds.scene.format

import gg.grounds.scene.format.internal.immutableListCopy

sealed interface LookBehavior {
    data object Fixed : LookBehavior

    data class TrackNearest(
        val maxDistance: Double,
        val yawOnly: Boolean,
        val maxTurnDegreesPerSecond: Double,
    ) : LookBehavior {
        init {
            requirePositiveFinite(maxDistance, "Look max distance")
            requirePositiveFinite(maxTurnDegreesPerSecond, "Look turn rate")
        }
    }
}

enum class SceneTrigger {
    LEFT_CLICK,
    RIGHT_CLICK,
    HOVER_ENTER,
    HOVER_LEAVE,
    PROXIMITY_ENTER,
    PROXIMITY_LEAVE,
}

data class ProximitySensor(val enterRadius: Double, val exitRadius: Double) {
    init {
        requirePositiveFinite(enterRadius, "Proximity enter radius")
        require(exitRadius.isFinite() && exitRadius > enterRadius) {
            "Proximity exit radius must be finite and greater than the enter radius."
        }
    }
}

@ConsistentCopyVisibility
data class TriggerBinding
private constructor(
    val trigger: SceneTrigger,
    val conditions: List<SceneCondition>,
    val cooldownMillis: Long,
    val debounceMillis: Long,
    val actions: List<SceneAction>,
    @Suppress("unused") private val canonical: Unit,
) {
    constructor(
        trigger: SceneTrigger,
        conditions: List<SceneCondition>,
        cooldownMillis: Long,
        debounceMillis: Long,
        actions: List<SceneAction>,
    ) : this(
        trigger,
        immutableListCopy(conditions),
        cooldownMillis,
        debounceMillis,
        immutableListCopy(actions),
        Unit,
    )

    init {
        require(cooldownMillis >= 0) { "Cooldown must be non-negative." }
        require(debounceMillis >= 0) { "Debounce must be non-negative." }
        require(actions.isNotEmpty()) { "Trigger bindings require at least one action." }
    }
}

enum class SceneHand {
    MAIN,
    OFF,
}

enum class SceneGameMode {
    SURVIVAL,
    CREATIVE,
    ADVENTURE,
    SPECTATOR,
}

sealed interface SceneCondition

data class HandCondition(val hand: SceneHand) : SceneCondition

data class SneakingCondition(val sneaking: Boolean) : SceneCondition

data class PermissionCondition(val permission: String) : SceneCondition {
    init {
        require(permission.isNotEmpty() && permission.all { it.code in 0x21..0x7e }) {
            "Permission must contain printable ASCII characters without whitespace."
        }
    }
}

data class GameModeCondition(val gameMode: SceneGameMode) : SceneCondition

internal fun requirePositiveFinite(value: Double, name: String) {
    require(value.isFinite() && value > 0.0) { "$name must be finite and positive." }
}

internal fun requireNonNegativeFinite(value: Double, name: String) {
    require(value.isFinite() && value >= 0.0) { "$name must be finite and non-negative." }
}
