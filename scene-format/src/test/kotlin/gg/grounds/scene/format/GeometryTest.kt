package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeometryTest {
    @Test
    fun `rotation canonicalizes order range and negative zero`() {
        assertEquals(EulerRotation(0.0, -180.0, 0.0), EulerRotation(-0.0, 180.0, 360.0))
    }

    @Test
    fun `vectors reject non-finite components and canonicalize negative zero`() {
        assertFailsWith<IllegalArgumentException> { Vec3(Double.NaN, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Vec3(0.0, Double.POSITIVE_INFINITY, 0.0) }
        assertEquals(Vec3(0.0, 1.25, -3.5), Vec3(-0.0, 1.25, -3.5))
    }

    @Test
    fun `transform rejects non-finite and non-positive scale`() {
        assertFailsWith<IllegalArgumentException> { Transform(ORIGIN, ZERO_ROTATION, Vec3(1.0, 0.0, 1.0)) }
        assertFailsWith<IllegalArgumentException> { Transform(ORIGIN, ZERO_ROTATION, Vec3(1.0, -1.0, 1.0)) }
    }

    @Test
    fun `local bounds require a positive finite size`() {
        assertFailsWith<IllegalArgumentException> { LocalBounds(ORIGIN, Vec3(1.0, 0.0, 1.0)) }
    }
}
