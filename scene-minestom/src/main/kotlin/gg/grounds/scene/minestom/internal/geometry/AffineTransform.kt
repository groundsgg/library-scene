package gg.grounds.scene.minestom.internal.geometry

import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.SceneRenderTransform
import kotlin.math.cos
import kotlin.math.sin

internal class AffineTransform private constructor(private val values: DoubleArray) {
    operator fun times(other: AffineTransform): AffineTransform {
        val result = DoubleArray(16)
        for (row in 0..3) {
            for (column in 0..3) {
                result[row * 4 + column] = (0..3).sumOf { values[row * 4 + it] * other.values[it * 4 + column] }
            }
        }
        return AffineTransform(result)
    }

    fun transform(point: Vec3): Vec3 = Vec3(
        values[0] * point.x + values[1] * point.y + values[2] * point.z + values[3],
        values[4] * point.x + values[5] * point.y + values[6] * point.z + values[7],
        values[8] * point.x + values[9] * point.y + values[10] * point.z + values[11],
    )

    companion object {
        fun from(transform: Transform): AffineTransform {
            val yaw = Math.toRadians(transform.rotation.yaw)
            val pitch = Math.toRadians(transform.rotation.pitch)
            val roll = Math.toRadians(transform.rotation.roll)
            return translation(transform.position) * yawY(yaw) * pitchX(pitch) * rollZ(roll) * scale(transform.scale)
        }

        private fun translation(position: Vec3) = AffineTransform(
            doubleArrayOf(1.0, 0.0, 0.0, position.x, 0.0, 1.0, 0.0, position.y, 0.0, 0.0, 1.0, position.z, 0.0, 0.0, 0.0, 1.0),
        )

        private fun yawY(angle: Double): AffineTransform {
            val cosine = cos(angle)
            val sine = sin(angle)
            return AffineTransform(doubleArrayOf(cosine, 0.0, sine, 0.0, 0.0, 1.0, 0.0, 0.0, -sine, 0.0, cosine, 0.0, 0.0, 0.0, 0.0, 1.0))
        }

        private fun pitchX(angle: Double): AffineTransform {
            val cosine = cos(angle)
            val sine = sin(angle)
            return AffineTransform(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, cosine, -sine, 0.0, 0.0, sine, cosine, 0.0, 0.0, 0.0, 0.0, 1.0))
        }

        private fun rollZ(angle: Double): AffineTransform {
            val cosine = cos(angle)
            val sine = sin(angle)
            return AffineTransform(doubleArrayOf(cosine, -sine, 0.0, 0.0, sine, cosine, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        }

        private fun scale(scale: Vec3) = AffineTransform(
            doubleArrayOf(scale.x, 0.0, 0.0, 0.0, 0.0, scale.y, 0.0, 0.0, 0.0, 0.0, scale.z, 0.0, 0.0, 0.0, 0.0, 1.0),
        )
    }
}

internal fun SceneRenderTransform.affine(): AffineTransform =
    AffineTransform.from(root).let { rootMatrix -> local?.let { rootMatrix * AffineTransform.from(it) } ?: rootMatrix }
