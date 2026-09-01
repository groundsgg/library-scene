package gg.grounds.scene.minestom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SceneRuntimeConfigTest {
    @Test
    fun `uses spatial runtime defaults`() {
        assertEquals(
            SceneRuntimeConfig(
                cellEdge = 32.0,
                activationDistance = 64.0,
                deactivationDistance = 80.0,
                deactivationGraceMillis = 5_000,
                spatialIntervalTicks = 10,
                transitionBudgetPerTick = 256,
            ),
            SceneRuntimeConfig(),
        )
    }

    @Test
    fun `rejects invalid spatial runtime values`() {
        listOf(
                { SceneRuntimeConfig(cellEdge = Double.NaN) },
                { SceneRuntimeConfig(cellEdge = 0.0) },
                { SceneRuntimeConfig(activationDistance = Double.POSITIVE_INFINITY) },
                { SceneRuntimeConfig(activationDistance = -1.0) },
                { SceneRuntimeConfig(deactivationDistance = 0.0) },
                { SceneRuntimeConfig(activationDistance = 65.0, deactivationDistance = 64.0) },
                { SceneRuntimeConfig(deactivationGraceMillis = -1) },
                { SceneRuntimeConfig(spatialIntervalTicks = 0) },
                { SceneRuntimeConfig(transitionBudgetPerTick = 0) },
            )
            .forEach { invalid -> assertFailsWith<IllegalArgumentException> { invalid() } }
    }
}
