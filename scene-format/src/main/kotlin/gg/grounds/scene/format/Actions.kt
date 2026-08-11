package gg.grounds.scene.format

import gg.grounds.scene.format.internal.immutableMapCopy
import java.math.BigDecimal
import net.kyori.adventure.text.Component

data class ElementTarget(val element: LocalId, val part: LocalId?)

sealed interface SceneAction

data class StartAnimationAction(val target: ElementTarget, val animation: LocalId) : SceneAction

data class StopAnimationAction(val target: ElementTarget, val animation: LocalId?) : SceneAction

data class PlaySoundAction(val sound: AssetKey, val volume: Double, val pitch: Double) :
    SceneAction {
    init {
        requirePositiveFinite(volume, "Sound volume")
        requirePositiveFinite(pitch, "Sound pitch")
    }
}

data class SetViewerScaleAction(
    val target: ElementTarget,
    val multiplier: Double,
    val transitionMillis: Long,
) : SceneAction {
    init {
        requirePositiveFinite(multiplier, "Viewer scale multiplier")
        requireNonNegativeMillis(transitionMillis, "Viewer scale transition")
    }
}

data class SetViewerHighlightAction(
    val target: ElementTarget,
    val enabled: Boolean,
    val transitionMillis: Long,
) : SceneAction {
    init {
        requireNonNegativeMillis(transitionMillis, "Viewer highlight transition")
    }
}

data class SendMessageAction(val message: Component) : SceneAction

data class SendActionBarAction(val message: Component) : SceneAction

data class ShowTitleAction(
    val title: Component,
    val subtitle: Component,
    val fadeInMillis: Long,
    val stayMillis: Long,
    val fadeOutMillis: Long,
) : SceneAction {
    init {
        requireNonNegativeMillis(fadeInMillis, "Title fade-in")
        requireNonNegativeMillis(stayMillis, "Title stay")
        requireNonNegativeMillis(fadeOutMillis, "Title fade-out")
    }
}

data class EmitParticleAction(
    val target: ElementTarget,
    val particle: AssetKey,
    val count: Int,
    val offset: Vec3,
    val speed: Double,
) : SceneAction {
    init {
        require(count >= 0) { "Particle count must be non-negative." }
        requireNonNegativeFinite(speed, "Particle speed")
    }
}

sealed interface ApplicationArgument

data class StringArgument(val value: String) : ApplicationArgument

data class LongArgument(val value: Long) : ApplicationArgument

data class DecimalArgument(val value: BigDecimal) : ApplicationArgument {
    init {
        requireCanonicalDecimal(value)
    }
}

data class BooleanArgument(val value: Boolean) : ApplicationArgument

data class EnumArgument(val value: LocalId) : ApplicationArgument

data class AssetArgument(val value: AssetKey) : ApplicationArgument

@ConsistentCopyVisibility
data class ApplicationAction
private constructor(
    val key: ActionKey,
    val arguments: Map<LocalId, ApplicationArgument>,
    @Suppress("unused") private val canonical: Unit,
) : SceneAction {
    constructor(
        key: ActionKey,
        arguments: Map<LocalId, ApplicationArgument>,
    ) : this(key, immutableMapCopy(arguments), Unit)
}

private fun requireNonNegativeMillis(value: Long, name: String) {
    require(value >= 0) { "$name must be non-negative." }
}

internal fun requireCanonicalDecimal(value: BigDecimal): BigDecimal {
    val canonical = value.stripTrailingZeros()
    val precision = canonical.precision().toLong()
    val scale = canonical.scale().toLong()
    val signLength = if (canonical.signum() < 0) 1L else 0L
    val plainLength =
        when {
            canonical.signum() == 0 -> 1L
            scale <= 0L -> signLength + precision - scale
            precision > scale -> signLength + precision + 1L
            else -> signLength + scale + 2L
        }
    require(plainLength <= 128L) { "Canonical decimal exceeds 128 characters." }
    return value
}
