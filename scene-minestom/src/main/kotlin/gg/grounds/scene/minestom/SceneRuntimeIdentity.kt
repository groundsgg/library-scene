package gg.grounds.scene.minestom

import gg.grounds.scene.format.SceneId

data class SceneRuntimeIdentity(val sceneId: SceneId, val mapId: String, val mapVersion: Long) {
    val sceneIdValue: String
        get() = sceneId.value
}
