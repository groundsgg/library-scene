package gg.grounds.scene.format

enum class NpcCollisionMode {
    NONE,
    SOLID,
    BOUNCE,
}

data class NpcCollision(
    val mode: NpcCollisionMode,
    val bounceStrength: Double,
    val bounceDistance: Double?,
) {
    constructor(
        mode: NpcCollisionMode = NpcCollisionMode.NONE,
        bounceStrength: Double = 6.0,
    ) : this(mode, bounceStrength, null)

    constructor() : this(NpcCollisionMode.NONE, 6.0, null)

    init {
        require(bounceStrength.isFinite() && bounceStrength in 0.0..20.0) {
            "Bounce strength must be finite and between 0.0 and 20.0 inclusive."
        }
        require(
            bounceDistance == null || (bounceDistance.isFinite() && bounceDistance in 0.0..20.0)
        ) {
            "Bounce distance must be finite and between 0.0 and 20.0 blocks inclusive."
        }
        require(bounceDistance == null || bounceStrength == 6.0) {
            "Bounce distance and a non-default legacy strength cannot be combined."
        }
    }

    /** Keeps the published two-argument copy and its Kotlin default-mask descriptor. */
    fun copy(
        mode: NpcCollisionMode = this.mode,
        bounceStrength: Double = this.bounceStrength,
    ): NpcCollision =
        NpcCollision(
            mode,
            bounceStrength,
            bounceDistance.takeIf { bounceStrength == this.bounceStrength },
        )
}
