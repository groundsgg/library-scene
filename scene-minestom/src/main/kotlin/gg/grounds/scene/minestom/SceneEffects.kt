package gg.grounds.scene.minestom

import gg.grounds.scene.format.*
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

interface SceneEffectSink {
    fun supports(asset: AssetKey, kind: AssetKind): Boolean
    fun playSound(player: Player, sound: AssetKey, volume: Double, pitch: Double)
    fun emitParticle(instance: Instance, particle: AssetKey, point: Point, count: Int, offset: Vec3, speed: Double)
}
