package gg.grounds.scene.format

enum class ActivationPolicy { AUTOMATIC, ALWAYS }

sealed interface SceneElement {
    val id: LocalId
    val group: LocalId?
    val transform: Transform
    val visible: Boolean
    val activation: ActivationPolicy
}
