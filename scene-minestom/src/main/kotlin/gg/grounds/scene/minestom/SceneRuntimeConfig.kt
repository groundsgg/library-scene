package gg.grounds.scene.minestom

data class SceneRuntimeConfig(
    val cellEdge: Double = 32.0,
    val activationDistance: Double = 64.0,
    val deactivationDistance: Double = 80.0,
    val deactivationGraceMillis: Long = 5_000,
    val spatialIntervalTicks: Int = 10,
    val transitionBudgetPerTick: Int = 256,
) {
    init {
        require(cellEdge.isFinite() && cellEdge > 0.0) { "Cell edge must be finite and positive." }
        require(activationDistance.isFinite() && activationDistance > 0.0) {
            "Activation distance must be finite and positive."
        }
        require(deactivationDistance.isFinite() && deactivationDistance > 0.0) {
            "Deactivation distance must be finite and positive."
        }
        require(deactivationDistance >= activationDistance) {
            "Deactivation distance must not be smaller than activation distance."
        }
        require(deactivationGraceMillis >= 0) { "Deactivation grace must be non-negative." }
        require(spatialIntervalTicks > 0) { "Spatial interval ticks must be positive." }
        require(transitionBudgetPerTick > 0) { "Transition budget per tick must be positive." }
    }
}
