package gg.grounds.scene.minestom

import gg.grounds.scene.format.ActionKey
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/** Java-friendly bridge for a renderer registry while preserving the typed Kotlin SPI. */
abstract class JavaSceneAssetRendererRegistry : SceneAssetRendererRegistry {
    final override fun rendererFor(asset: AssetKey, kind: AssetKind): SceneAssetRendererFactory? =
        rendererFor(asset.value, kind)

    abstract fun rendererFor(asset: String, kind: AssetKind): SceneAssetRendererFactory?
}

/** Java-friendly bridge for effects while preserving the typed Kotlin SPI. */
abstract class JavaSceneEffectSink : SceneEffectSink {
    final override fun supports(asset: AssetKey, kind: AssetKind): Boolean =
        supports(asset.value, kind)

    final override fun playSound(player: Player, sound: AssetKey, volume: Double, pitch: Double) =
        playSound(player, sound.value, volume, pitch)

    final override fun emitParticle(
        instance: Instance,
        particle: AssetKey,
        point: Point,
        count: Int,
        offset: gg.grounds.scene.format.Vec3,
        speed: Double,
    ) = emitParticle(instance, particle.value, point, count, offset, speed)

    abstract fun supports(asset: String, kind: AssetKind): Boolean

    abstract fun playSound(player: Player, sound: String, volume: Double, pitch: Double)

    abstract fun emitParticle(
        instance: Instance,
        particle: String,
        point: Point,
        count: Int,
        offset: gg.grounds.scene.format.Vec3,
        speed: Double,
    )
}

/** Java-friendly bridge for action lookup while preserving the typed Kotlin SPI. */
abstract class JavaSceneActionRegistry : SceneActionRegistry {
    final override fun handlerFor(key: ActionKey): SceneActionHandler? = handlerFor(key.value)

    abstract fun handlerFor(key: String): SceneActionHandler?
}

object SceneRuntimeJava {
    @JvmStatic
    fun identity(sceneId: String, mapId: String, mapVersion: Long): SceneRuntimeIdentity =
        SceneRuntimeIdentity(gg.grounds.scene.format.SceneId(sceneId), mapId, mapVersion)
}
