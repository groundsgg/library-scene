package gg.grounds.scene.format

enum class NpcCollisionMode {
    NONE,
    SOLID,
    BOUNCE,
}

data class NpcCollision(
    val mode: NpcCollisionMode = NpcCollisionMode.NONE,
    val bounceStrength: Double = 6.0,
) {
    init {
        require(bounceStrength.isFinite() && bounceStrength in 0.0..20.0) {
            "Bounce strength must be finite and between 0.0 and 20.0 inclusive."
        }
    }
}
