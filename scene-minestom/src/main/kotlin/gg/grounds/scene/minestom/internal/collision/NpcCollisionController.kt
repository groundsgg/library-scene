package gg.grounds.scene.minestom.internal.collision

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.NpcCollision
import gg.grounds.scene.format.NpcCollisionMode
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.ScenePlayerPolicy
import gg.grounds.scene.minestom.internal.elapsedNanos
import gg.grounds.scene.minestom.internal.runtime.ActiveElement
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import net.minestom.server.MinecraftServer
import net.minestom.server.collision.BoundingBox
import net.minestom.server.collision.SweepResult
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.EventListener
import net.minestom.server.event.entity.EntityVelocityEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.EntityTracker
import net.minestom.server.instance.Instance
import org.slf4j.LoggerFactory

internal class NpcCollisionController(
    private val instance: Instance,
    private val playerPolicy: ScenePlayerPolicy,
    private val clock: SceneClock,
    private val schedule: (Player, Runnable) -> Unit,
    private val interactionElementId: (UUID) -> LocalId?,
    private val activeElement: (LocalId) -> ActiveElement?,
) : AutoCloseable {
    private val colliders = mutableMapOf<ColliderIdentity, ColliderExtent>()
    private val extentCounts = TreeMap<Double, Int>()
    private val contacts = mutableMapOf<UUID, Contact>()
    private val lastImpulseNanos = mutableMapOf<UUID, Long>()
    private var maximumExtent = 0.0
    private var revision = 0L
    private var closed = false

    fun colliderChanged(active: ActiveElement) {
        val identity = ColliderIdentity(active.elementId, active.generation)
        val npc = active.npc
        val interaction = active.npcEntities?.interaction
        val geometry =
            if (
                !closed &&
                    npc != null &&
                    npc.visible &&
                    npc.collision.mode != NpcCollisionMode.NONE &&
                    interaction?.instance === instance
            ) {
                val evaluated = npc.requireSupportedCollisionGeometry(active.currentTransform)
                val bounds = evaluated.bounds
                ColliderGeometry(
                    interaction.uuid,
                    bounds.min.x,
                    bounds.min.y,
                    bounds.min.z,
                    bounds.max.x,
                    bounds.max.y,
                    bounds.max.z,
                    evaluated.radius,
                )
            } else {
                null
            }
        val previous = colliders[identity]
        if (previous?.geometry == geometry) return
        if (previous != null) {
            colliders.remove(identity)
            removeExtent(previous.geometry.radius)
        }
        if (geometry != null) {
            colliders[identity] = ColliderExtent(++revision, geometry)
            addExtent(geometry.radius)
        } else revision++
        if (geometry == null) contacts.entries.removeIf { it.value.identity == identity }
    }

    fun colliderRemoved(active: ActiveElement) {
        val identity = ColliderIdentity(active.elementId, active.generation)
        colliders.remove(identity)?.let { removeExtent(it.geometry.radius) }
        contacts.entries.removeIf { it.value.identity == identity }
        revision++
    }

    @Suppress("DEPRECATION") // TPS facade shared by the July ABI and October runtime.
    fun move(event: PlayerMoveEvent) {
        if (closed || event.isCancelled) return
        val player = event.player
        val old = player.position
        val requested = event.newPosition
        val delta = Vec(requested.x() - old.x(), requested.y() - old.y(), requested.z() - old.z())
        if (delta.x() == 0.0 && delta.y() == 0.0 && delta.z() == 0.0) return
        if (maximumExtent == 0.0 || player.instance !== instance) return
        if (player.gameMode == GameMode.SPECTATOR || !eligible(player)) {
            contacts.remove(player.uuid)
            return
        }

        val playerBox = player.boundingBox
        val playerExtent =
            sqrt(
                max(abs(playerBox.minX()), abs(playerBox.maxX())).let { it * it } +
                    max(abs(playerBox.minY()), abs(playerBox.maxY())).let { it * it } +
                    max(abs(playerBox.minZ()), abs(playerBox.maxZ())).let { it * it }
            )
        val queryRadius = delta.length() + playerExtent + maximumExtent
        if (!queryRadius.isFinite() || queryRadius > MAX_NPC_QUERY_RADIUS) {
            event.newPosition = Pos(old.x(), old.y(), old.z(), requested.yaw(), requested.pitch())
            return
        }
        val candidates = mutableListOf<Entity>()
        instance.entityTracker.nearbyEntities(
            old,
            queryRadius,
            EntityTracker.Target.ENTITIES,
            candidates::add,
        )
        val hit =
            candidates
                .asSequence()
                .mapNotNull { candidate(candidate = it, player = player, old = old, delta = delta) }
                .minWithOrNull(
                    compareBy<Hit> { it.entry }.thenBy { it.key.identity.elementId.value }
                )
        if (hit == null) {
            contacts.remove(player.uuid)
            return
        }

        val interaction = checkNotNull(hit.active.npcEntities).interaction
        val correctedHeight =
            if (
                hit.horizontal &&
                    !interaction.boundingBox.intersectBox(
                        interaction.position,
                        playerBoxAt(old.withY(requested.y()), playerBox),
                    )
            )
                requested.y()
            else old.y()
        val corrected = Pos(old.x(), correctedHeight, old.z(), requested.yaw(), requested.pitch())
        event.newPosition = corrected
        val previous = contacts[player.uuid]
        val contact =
            if (previous?.identity == hit.key.identity) previous
            else Contact(hit.key.identity, ++revision).also { contacts[player.uuid] = it }
        val npc = hit.active.npc ?: return
        if (
            npc.collision.mode != NpcCollisionMode.BOUNCE ||
                npc.collision.impulseSpeed() == 0.0 ||
                contact.delivered ||
                contact.pending ||
                !minimumIntervalElapsed(player.uuid)
        ) {
            return
        }
        val accepted = AcceptedPosition(corrected.x(), corrected.y(), corrected.z())
        val groundedContact = event.isOnGround && player.isOnGround
        val token = contact.token
        contact.pending = true
        try {
            schedule(
                player,
                Runnable {
                    try {
                        val currentContact = contacts[player.uuid]
                        if (
                            closed ||
                                currentContact?.token != token ||
                                player.instance !== instance ||
                                player.gameMode == GameMode.SPECTATOR ||
                                !accepted.matches(player.position) ||
                                !eligible(player)
                        ) {
                            return@Runnable
                        }
                        val currentHit =
                            currentHit(hit.key.identity, playerBox, old, delta) ?: return@Runnable
                        val currentStrength =
                            currentHit.active.npc?.collision?.impulseSpeed() ?: return@Runnable
                        if (!minimumIntervalElapsed(player.uuid)) return@Runnable
                        val velocity = player.velocity
                        val delivered =
                            applyVelocity(
                                player,
                                Vec(
                                    currentHit.outward.x() * currentStrength,
                                    if (player.isFlying) velocity.y()
                                    else if (groundedContact && player.isOnGround)
                                        maxOf(velocity.y(), GROUNDED_BOUNCE_LIFT)
                                    else if (
                                        currentHit.horizontal && correctedHeight == requested.y()
                                    )
                                        (delta.y() * MinecraftServer.TICK_PER_SECOND).coerceIn(
                                            -MAX_AIRBORNE_SPEED,
                                            MAX_AIRBORNE_SPEED,
                                        )
                                    else 0.0,
                                    currentHit.outward.z() * currentStrength,
                                ),
                            )
                        if (!delivered) return@Runnable
                        lastImpulseNanos[player.uuid] = clock.nanoTime()
                        currentContact.delivered = true
                    } finally {
                        contact.pending = false
                    }
                },
            )
        } catch (error: Throwable) {
            contact.pending = false
            throw error
        }
    }

    fun disconnect(playerId: UUID) {
        contacts.remove(playerId)
        lastImpulseNanos.remove(playerId)
    }

    override fun close() {
        closed = true
        colliders.clear()
        extentCounts.clear()
        contacts.clear()
        lastImpulseNanos.clear()
        maximumExtent = 0.0
        revision++
    }

    private fun candidate(candidate: Entity, player: Player, old: Pos, delta: Vec): Hit? {
        if (candidate === player || candidate.instance !== instance) return null
        val elementId = interactionElementId(candidate.uuid) ?: return null
        val active = activeElement(elementId) ?: return null
        if (active.npcEntities?.interaction !== candidate) return null
        val npc = active.npc ?: return null
        if (!npc.visible || npc.collision.mode == NpcCollisionMode.NONE) return null
        val identity = ColliderIdentity(elementId, active.generation)
        val extent = colliders[identity] ?: return null
        return sweepHit(active, candidate, player.boundingBox, old, delta, identity, extent)
    }

    private fun sweepHit(
        active: ActiveElement,
        candidate: Entity,
        playerBox: BoundingBox,
        old: Pos,
        delta: Vec,
        identity: ColliderIdentity,
        extent: ColliderExtent,
    ): Hit? {
        val key = ColliderKey(identity, extent.revision)
        val colliderBox = candidate.boundingBox
        val colliderPosition = candidate.position
        if (colliderBox.intersectBox(colliderPosition, playerBoxAt(old, playerBox))) return null
        val sweep = SweepResult(1.0, 0.0, 0.0, 0.0, null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        if (!colliderBox.intersectBoxSwept(old, delta, colliderPosition, playerBox, sweep))
            return null
        val entry = slabEntry(colliderBox, colliderPosition, playerBox, old, delta) ?: return null
        return Hit(active, key, entry.fraction, entry.outward, entry.horizontal)
    }

    private fun currentHit(
        identity: ColliderIdentity,
        playerBox: BoundingBox,
        old: Pos,
        delta: Vec,
    ): Hit? {
        val extent = colliders[identity] ?: return null
        val active = activeElement(identity.elementId) ?: return null
        if (active.generation != identity.generation) return null
        val npc = active.npc ?: return null
        val interaction = active.npcEntities?.interaction ?: return null
        if (
            !npc.visible ||
                npc.collision.mode != NpcCollisionMode.BOUNCE ||
                npc.collision.impulseSpeed() == 0.0 ||
                interaction.instance !== instance ||
                interactionElementId(interaction.uuid) != identity.elementId
        ) {
            return null
        }
        return sweepHit(active, interaction, playerBox, old, delta, identity, extent)
    }

    private fun eligible(player: Player): Boolean =
        try {
            playerPolicy.isEligible(player)
        } catch (error: Throwable) {
            LOGGER.error("NPC collision player policy failed for player={}", player.uuid, error)
            false
        }

    private fun applyVelocity(player: Player, velocity: Vec): Boolean {
        var observed: EntityVelocityEvent? = null
        val observer =
            EventListener.builder(EntityVelocityEvent::class.java)
                .ignoreCancelled(false)
                .handler { event -> if (event.entity === player) observed = event }
                .build()
        val eventNode = instance.eventNode()
        eventNode.addListener(observer)
        try {
            player.velocity = velocity
        } finally {
            eventNode.removeListener(observer)
        }
        return observed?.isCancelled == false
    }

    private fun minimumIntervalElapsed(playerId: UUID): Boolean {
        val last = lastImpulseNanos[playerId] ?: return true
        return elapsedNanos(clock.nanoTime(), last) >= BOUNCE_INTERVAL_NANOS
    }

    private fun addExtent(extent: Double) {
        extentCounts.merge(extent, 1, Int::plus)
        maximumExtent = extentCounts.lastKey()
    }

    private fun removeExtent(extent: Double) {
        val count = extentCounts[extent] ?: return
        if (count == 1) extentCounts.remove(extent) else extentCounts[extent] = count - 1
        maximumExtent = if (extentCounts.isEmpty()) 0.0 else extentCounts.lastKey()
    }

    private data class ColliderIdentity(val elementId: LocalId, val generation: Long)

    private data class ColliderKey(val identity: ColliderIdentity, val revision: Long)

    private data class ColliderExtent(val revision: Long, val geometry: ColliderGeometry)

    private data class ColliderGeometry(
        val entityId: UUID,
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double,
        val radius: Double,
    )

    private data class Contact(
        val identity: ColliderIdentity,
        val token: Long,
        var pending: Boolean = false,
        var delivered: Boolean = false,
    )

    private data class Hit(
        val active: ActiveElement,
        val key: ColliderKey,
        val entry: Double,
        val outward: Vec,
        val horizontal: Boolean,
    )

    private data class AcceptedPosition(val x: Double, val y: Double, val z: Double) {
        fun matches(position: Point) = position.x() == x && position.y() == y && position.z() == z
    }

    private companion object {
        // 0.2 blocks/tick: a small hop, well below an ordinary jump.
        const val GROUNDED_BOUNCE_LIFT = 4.0
        // Bound displacement estimates to the legacy velocity packet's 3.9 blocks/tick.
        const val MAX_AIRBORNE_SPEED = 78.0
        val BOUNCE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250)
        val LOGGER = LoggerFactory.getLogger(NpcCollisionController::class.java)
    }
}

/** Approximate free travel on ordinary ground: sum of velocities with drag 0.6 * 0.91. */
@Suppress("DEPRECATION") // The TPS facade is shared by the July compile ABI and October runtime.
private fun NpcCollision.impulseSpeed(): Double =
    bounceDistance?.let { it * MinecraftServer.TICK_PER_SECOND * (1.0 - 0.6 * 0.91) }
        ?: bounceStrength

private data class SlabEntry(val fraction: Double, val outward: Vec, val horizontal: Boolean)

private fun slabEntry(
    collider: BoundingBox,
    colliderPosition: Point,
    moving: BoundingBox,
    movingPosition: Point,
    delta: Point,
): SlabEntry? {
    val minimum =
        doubleArrayOf(
            colliderPosition.x() + collider.minX() - moving.maxX(),
            colliderPosition.y() + collider.minY() - moving.maxY(),
            colliderPosition.z() + collider.minZ() - moving.maxZ(),
        )
    val maximum =
        doubleArrayOf(
            colliderPosition.x() + collider.maxX() - moving.minX(),
            colliderPosition.y() + collider.maxY() - moving.minY(),
            colliderPosition.z() + collider.maxZ() - moving.minZ(),
        )
    val origin = doubleArrayOf(movingPosition.x(), movingPosition.y(), movingPosition.z())
    val motion = doubleArrayOf(delta.x(), delta.y(), delta.z())
    var enter = Double.NEGATIVE_INFINITY
    var exit = Double.POSITIVE_INFINITY
    var axis = -1
    for (index in 0..2) {
        val speed = motion[index]
        if (speed == 0.0) {
            if (origin[index] <= minimum[index] || origin[index] >= maximum[index]) return null
            continue
        }
        val first = (minimum[index] - origin[index]) / speed
        val second = (maximum[index] - origin[index]) / speed
        val near = minOf(first, second)
        val far = maxOf(first, second)
        if (near > enter) {
            enter = near
            axis = index
        }
        exit = minOf(exit, far)
        if (enter > exit) return null
    }
    if (enter !in 0.0..1.0 || axis < 0) return null
    val sign = if (motion[axis] > 0.0) -1.0 else 1.0
    val outward =
        when (axis) {
            0 -> Vec(sign, 0.0, 0.0)
            2 -> Vec(0.0, 0.0, sign)
            else -> {
                val x = movingPosition.x() + motion[0] * enter - colliderPosition.x()
                val z = movingPosition.z() + motion[2] * enter - colliderPosition.z()
                val length = sqrt(x * x + z * z)
                if (length == 0.0) Vec(-1.0, 0.0, 0.0) else Vec(x / length, 0.0, z / length)
            }
        }
    return SlabEntry(enter, outward, axis != 1)
}

private fun playerBoxAt(position: Point, boundingBox: BoundingBox): BoundingBox =
    BoundingBox(
        Vec(
            position.x() + boundingBox.minX(),
            position.y() + boundingBox.minY(),
            position.z() + boundingBox.minZ(),
        ),
        Vec(
            position.x() + boundingBox.maxX(),
            position.y() + boundingBox.maxY(),
            position.z() + boundingBox.maxZ(),
        ),
    )
