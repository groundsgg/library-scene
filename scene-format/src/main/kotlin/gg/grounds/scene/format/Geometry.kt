package gg.grounds.scene.format

@ConsistentCopyVisibility
data class Vec3
private constructor(
    val x: Double,
    val y: Double,
    val z: Double,
    @Suppress("unused") private val canonical: Unit,
) {
    constructor(
        x: Double,
        y: Double,
        z: Double,
    ) : this(canonicalComponent(x), canonicalComponent(y), canonicalComponent(z), Unit)
}

@ConsistentCopyVisibility
data class EulerRotation
private constructor(
    val yaw: Double,
    val pitch: Double,
    val roll: Double,
    @Suppress("unused") private val canonical: Unit,
) {
    constructor(
        yaw: Double,
        pitch: Double,
        roll: Double,
    ) : this(canonicalAngle(yaw), canonicalAngle(pitch), canonicalAngle(roll), Unit)
}

data class Transform(val position: Vec3, val rotation: EulerRotation, val scale: Vec3) {
    init {
        require(scale.x > 0.0 && scale.y > 0.0 && scale.z > 0.0) {
            "Transform scale must be positive."
        }
    }
}

data class LocalBounds(val center: Vec3, val size: Vec3) {
    init {
        require(size.x > 0.0 && size.y > 0.0 && size.z > 0.0) {
            "Local bounds size must be positive."
        }
    }
}

val ORIGIN = Vec3(0.0, 0.0, 0.0)
val ZERO_ROTATION = EulerRotation(0.0, 0.0, 0.0)

private fun canonicalComponent(value: Double): Double {
    require(value.isFinite()) { "Vector components must be finite." }
    return if (value == 0.0) 0.0 else value
}

private fun canonicalAngle(value: Double): Double {
    require(value.isFinite()) { "Rotation angles must be finite." }
    val normalized = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return if (normalized == 0.0) 0.0 else normalized
}
