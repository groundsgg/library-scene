package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.CompositeProp
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.NpcCollisionMode
import gg.grounds.scene.format.Prop
import gg.grounds.scene.format.SceneElement
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.RenderedAssetHandle
import gg.grounds.scene.minestom.SceneAssetRenderContext
import gg.grounds.scene.minestom.SceneAssetRendererFactory
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.ScopedSceneAssetRendererFactory
import gg.grounds.scene.minestom.internal.RendererCapabilityKey
import gg.grounds.scene.minestom.internal.collision.requireSupportedCollisionGeometry
import gg.grounds.scene.minestom.internal.elapsedNanos
import gg.grounds.scene.minestom.internal.geometry.affine
import gg.grounds.scene.minestom.internal.geometry.transformed
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import kotlin.math.max
import net.minestom.server.collision.BoundingBox
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.server.entity.metadata.other.InteractionMeta
import net.minestom.server.instance.Instance

internal interface ElementActivation {
    val stage: CompletionStage<ActiveElement>

    fun claim(active: ActiveElement): Boolean

    fun abort(): Throwable?

    /** Thread-safe invalidation only; legacy owner-confined resource disposal is unchanged. */
    fun invalidateRenderers(): Throwable?
}

internal class ElementActivator(
    private val instance: Instance,
    private val rendererFactories: Map<RendererCapabilityKey, SceneAssetRendererFactory>,
    private val clock: SceneClock,
    private val schedule: (Runnable) -> Unit = Runnable::run,
    private val colliderChanged: (ActiveElement) -> Unit = {},
    private val rendererFailure: (LocalId, Throwable) -> Unit = { _, _ -> },
) {
    private val interactionIds = mutableMapOf<UUID, LocalId>()
    private val resourceDrain = ResourceDrain()

    fun beginCloseDrain(): CompletionStage<Pair<Throwable?, Boolean>> = resourceDrain.beginClose()

    private fun closeOwnedResources(
        npc: NpcPlatformEntities?,
        handles: List<RenderedAssetHandle>,
        failure: Throwable? = null,
    ): Throwable? =
        gg.grounds.scene.minestom.internal.runtime.closeOwnedResources(
            npc,
            handles,
            failure,
            resourceDrain::beginOperation,
            { error -> resourceDrain.completeOperation(error, closeMustFail = error != null) },
        )

    fun activate(state: LogicalElementState): CompletionStage<ActiveElement> =
        startActivation(state, false, null).stage

    fun beginActivation(
        state: LogicalElementState,
        onUnrecoverableContinuation: ((Throwable) -> Unit)? = null,
    ): ElementActivation = startActivation(state, true, onUnrecoverableContinuation)

    private fun startActivation(
        state: LogicalElementState,
        retainUntilClaimed: Boolean,
        onUnrecoverableContinuation: ((Throwable) -> Unit)?,
    ): ElementActivation {
        val generation = state.generation
        val requested = renderRequests(state.element)
        val npcElement = state.element as? Npc
        val activation =
            Activation(
                state,
                generation,
                requested,
                retainUntilClaimed,
                onUnrecoverableContinuation,
            )

        if (npcElement != null) {
            try {
                npcElement.requireSupportedCollisionGeometry(
                    SceneRenderTransform(npcElement.transform, null)
                )
            } catch (error: IllegalArgumentException) {
                activation.failed(error)
                return activation
            }
        }

        npcElement
            ?.takeIf { it.visible }
            ?.let { npc ->
                try {
                    val platformEntities = createNpcEntities(npc)
                    if (activation.attach(platformEntities)) {
                        platformEntities.instanceRequests(npc).forEach { (entity, position) ->
                            if (!activation.isOpen()) return@forEach
                            activation.beginNpcResource()
                            val stage =
                                try {
                                    requireNotNull(entity.setInstance(instance, position)) {
                                        "NPC entity attachment returned a null CompletionStage."
                                    }
                                } catch (error: Throwable) {
                                    activation.npcResourceCompleted(error)
                                    return@forEach
                                }
                            activation.trackNpcStage(stage)
                            observe(stage, activation) { _, error ->
                                activation.npcResourceCompleted(error?.unwrap())
                            }
                        }
                    }
                } catch (error: Throwable) {
                    activation.failed(error)
                }
            }
        requested.forEachIndexed { index, request ->
            if (!activation.isOpen()) return@forEachIndexed
            val stage =
                try {
                    val factory =
                        requireNotNull(
                            rendererFactories[
                                RendererCapabilityKey(request.context.asset, request.kind)]
                        ) {
                            "No renderer is available for ${request.context.asset.value}."
                        }
                    requireNotNull(
                        if (factory is ScopedSceneAssetRendererFactory)
                            factory.createScoped(request.context, activation.lifetime)
                        else factory.create(request.context)
                    ) {
                        "Renderer factory returned a null CompletionStage."
                    }
                } catch (error: Throwable) {
                    activation.failed(error)
                    return@forEachIndexed
                }
            observeRenderer(stage, index, activation)
        }
        if (requested.isEmpty() && npcElement?.visible != true) activation.completed()
        return activation
    }

    fun deactivate(active: ActiveElement) {
        active.npcEntities?.interaction?.uuid?.let(interactionIds::remove)
        active.close()
    }

    fun interactionElementId(interactionId: UUID): LocalId? = interactionIds[interactionId]

    private fun <T> observe(
        stage: CompletionStage<T>,
        activation: Activation,
        callback: (T?, Throwable?) -> Unit,
    ) {
        resourceDrain.beginOperation()
        try {
            stage.whenComplete { value, error ->
                try {
                    schedule(
                        Runnable {
                            var callbackFailure: Throwable? = null
                            try {
                                callback(value, error)
                            } catch (callbackError: Throwable) {
                                callbackFailure = callbackError
                                activation.failed(callbackError)
                            } finally {
                                resourceDrain.completeOperation(callbackFailure)
                            }
                        }
                    )
                } catch (scheduleError: Throwable) {
                    val failure = error?.unwrap().withSuppressed(scheduleError)
                    resourceDrain.completeOperation(failure, closeMustFail = true)
                    activation.recoverRejectedContinuation(failure) {
                        activation.npcResourceCompleted(failure)
                    }
                }
            }
        } catch (error: Throwable) {
            activation.npcResourceCompleted(error)
            resourceDrain.completeOperation(error)
        }
    }

    private fun observeRenderer(
        stage: CompletionStage<RenderedAssetHandle>,
        index: Int,
        activation: Activation,
    ) {
        resourceDrain.beginOperation()
        try {
            stage.whenComplete { handle, error ->
                try {
                    schedule(
                        Runnable {
                            var callbackFailure: Throwable? = null
                            try {
                                val delivery =
                                    activation.recordDelivery(index, handle, error?.unwrap())
                                activation.finishDelivery(delivery)
                            } catch (callbackError: Throwable) {
                                callbackFailure = callbackError
                                activation.failed(callbackError)
                            } finally {
                                resourceDrain.completeOperation(callbackFailure)
                            }
                        }
                    )
                } catch (scheduleError: Throwable) {
                    val failure = error?.unwrap().withSuppressed(scheduleError)
                    resourceDrain.completeOperation(failure, closeMustFail = true)
                    activation.recoverRejectedContinuation(failure) {
                        val delivery = activation.recordDelivery(index, handle, failure)
                        activation.finishDelivery(delivery)
                    }
                }
            }
        } catch (error: Throwable) {
            activation.failed(error)
            resourceDrain.completeOperation()
        }
    }

    private fun renderRequests(element: SceneElement): List<RenderRequest> =
        if (!element.visible) emptyList()
        else
            when (element) {
                is Prop ->
                    listOf(
                        RenderRequest(AssetKind.PROP, context(element, null, element.asset, null))
                    )
                is CompositeProp ->
                    element.parts
                        .sortedBy { it.id.value }
                        .map { part ->
                            RenderRequest(
                                AssetKind.PROP,
                                context(element, part.id, part.asset, part.transform),
                            )
                        }
                is Npc ->
                    listOf(
                        RenderRequest(
                            AssetKind.NPC_BODY,
                            context(element, null, element.body, null),
                        )
                    )
            }

    private fun context(
        element: SceneElement,
        partId: LocalId?,
        asset: gg.grounds.scene.format.AssetKey,
        local: gg.grounds.scene.format.Transform?,
    ) =
        SceneAssetRenderContext(
            instance,
            element.id,
            partId,
            asset,
            SceneRenderTransform(element.transform, local),
        )

    private fun createNpcEntities(npc: Npc): NpcPlatformEntities {
        val interaction = Entity(EntityType.INTERACTION)
        interaction.setNoGravity(true)
        var label: Entity? = null
        try {
            val transform = SceneRenderTransform(npc.transform, null)
            val bounds = npc.requireSupportedCollisionGeometry(transform).bounds
            interaction.boundingBox =
                if (npc.collision.mode == NpcCollisionMode.NONE) BoundingBox(0.0, 0.0, 0.0)
                else
                    BoundingBox(
                        bounds.max.x - bounds.min.x,
                        bounds.max.y - bounds.min.y,
                        bounds.max.z - bounds.min.z,
                    )
            (interaction.entityMeta as InteractionMeta).apply {
                width = max(bounds.max.x - bounds.min.x, bounds.max.z - bounds.min.z).toFloat()
                height = (bounds.max.y - bounds.min.y).toFloat()
                response = true
            }
            label =
                npc.label?.let { text ->
                    Entity(EntityType.TEXT_DISPLAY).also { entity ->
                        entity.setNoGravity(true)
                        (entity.entityMeta as TextDisplayMeta).text = text
                    }
                }
            return NpcPlatformEntities(label, interaction)
        } catch (error: Throwable) {
            throw closeOwnedResources(NpcPlatformEntities(label, interaction), emptyList(), error)
                ?: error
        }
    }

    private fun NpcPlatformEntities.instanceRequests(
        npc: Npc
    ): List<Pair<Entity, net.minestom.server.coordinate.Point>> {
        val transform = SceneRenderTransform(npc.transform, null)
        val bounds = npc.interactionBounds.transformed(transform.affine())
        val interactionPosition =
            Vec3(
                    (bounds.min.x + bounds.max.x) / 2.0,
                    bounds.min.y,
                    (bounds.min.z + bounds.max.z) / 2.0,
                )
                .asPoint()
        return buildList {
            label?.let { add(it to transform.affine().transform(npc.labelOffset).asPoint()) }
            add(interaction to interactionPosition)
        }
    }

    private fun Vec3.asPoint() = Vec(x, y, z)

    private data class RenderRequest(val kind: AssetKind, val context: SceneAssetRenderContext)

    private data class Delivery(
        val handle: RenderedAssetHandle?,
        val failure: Throwable?,
        val accepted: Boolean,
    )

    private inner class Activation(
        private val state: LogicalElementState,
        private val generation: Long,
        private val requests: List<RenderRequest>,
        private val retainUntilClaimed: Boolean,
        private val onUnrecoverableContinuation: ((Throwable) -> Unit)?,
    ) : ElementActivation {
        private val future = CompletableFuture<ActiveElement>()
        override val stage: CompletionStage<ActiveElement>
            get() = future

        val lifetime: RendererLifetime =
            RendererLifetime(
                { state.generation == generation && !resourceDrain.isClosing() },
                { error ->
                    try {
                        schedule(
                            Runnable {
                                if (!lifetime.isCurrent()) return@Runnable
                                if (!isOpen()) rendererFailure(state.element.id, error)
                                else failed(error)
                            }
                        )
                    } catch (scheduleError: Throwable) {
                        resourceDrain.beginOperation()
                        resourceDrain.completeOperation(
                            error.withSuppressed(scheduleError),
                            closeMustFail = true,
                        )
                    }
                },
                resourceDrain::trackCleanup,
            )
        private var pending: Int = requests.size
        private val handles = arrayOfNulls<RenderedAssetHandle>(requests.size)
        private var npc: NpcPlatformEntities? = null
        private var terminal = false
        private var terminalFailure: Throwable? = null
        private var published: ActiveElement? = null
        private var pendingNpcResources = 0
        private var npcCleanupDeferred = false
        private val npcStages = mutableListOf<CompletionStage<Void>>()

        fun recordDelivery(index: Int, handle: RenderedAssetHandle?, error: Throwable?): Delivery {
            val failure =
                error
                    ?: if (handle == null)
                        IllegalStateException("Renderer stage completed with a null handle.")
                    else null
            return synchronized(this) {
                if (terminal) Delivery(handle, failure, false)
                else {
                    if (handle != null) handles[index] = handle
                    Delivery(handle, failure, true)
                }
            }
        }

        fun finishDelivery(delivery: Delivery) {
            if (!delivery.accepted) {
                finishLateDelivery(delivery)
                return
            }
            synchronized(this) {
                if (terminal) {
                    recordLateCleanup(delivery.failure)
                    return
                }
                val failure = delivery.failure
                if (failure != null) failLocked(failure) else resourceCompletedLocked()
            }
        }

        private fun finishLateDelivery(delivery: Delivery) {
            val failure = delivery.failure
            val completedFailure =
                closeOwnedResources(null, listOfNotNull(delivery.handle), failure)
            synchronized(this) { recordLateCleanup(completedFailure) }
        }

        private fun recordLateCleanup(error: Throwable?) {
            if (error == null) return
            resourceDrain.recordFailure(error)
            terminalFailure?.let { failure -> if (error !== failure) failure.addSuppressed(error) }
        }

        fun isOpen(): Boolean = synchronized(this) { !terminal && lifetime.isCurrent() }

        override fun invalidateRenderers(): Throwable? =
            lifetime.invalidate().also { failure ->
                if (failure != null)
                    resourceDrain.trackCleanup(CompletableFuture.failedFuture<Void>(failure))
            }

        fun attach(platformEntities: NpcPlatformEntities): Boolean =
            synchronized(this) {
                if (terminal) {
                    recordLateCleanup(closeOwnedResources(platformEntities, emptyList()))
                    false
                } else {
                    npc = platformEntities
                    true
                }
            }

        fun beginNpcResource() =
            synchronized(this) {
                check(!terminal) { "NPC resource started after activation became terminal." }
                pendingNpcResources++
                pending++
            }

        fun trackNpcStage(stage: CompletionStage<Void>) = synchronized(this) { npcStages += stage }

        fun npcResourceCompleted(error: Throwable?) =
            synchronized(this) {
                check(pendingNpcResources > 0) { "NPC resource completed without ownership." }
                pendingNpcResources--
                if (!terminal) {
                    if (error == null) resourceCompletedLocked() else failLocked(error)
                }
                if (terminal && npcCleanupDeferred && pendingNpcResources == 0) {
                    npcCleanupDeferred = false
                    closeOwnedResources(npc, emptyList())?.let(::recordLateCleanup)
                }
            }

        fun completed() = synchronized(this) { if (!terminal) publishOrClose() }

        fun recoverRejectedContinuation(failure: Throwable, action: () -> Unit) {
            val unrecoverable = onUnrecoverableContinuation ?: return
            try {
                schedule(
                    Runnable {
                        try {
                            action()
                        } catch (callbackError: Throwable) {
                            failed(callbackError)
                        }
                    }
                )
            } catch (scheduleError: Throwable) {
                invalidateRenderers()?.let { failure.withSuppressed(it) }
                unrecoverable(failure.withSuppressed(scheduleError))
            }
        }

        fun failed(error: Throwable) {
            synchronized(this) {
                if (terminal) return
                failLocked(error)
            }
        }

        override fun claim(active: ActiveElement): Boolean =
            synchronized(this) {
                if (!retainUntilClaimed || published !== active) return@synchronized false
                published = null
                true
            }

        override fun abort(): Throwable? =
            synchronized(this) {
                published?.let { active ->
                    published = null
                    active.npcEntities?.interaction?.uuid?.let(interactionIds::remove)
                    return@synchronized try {
                        active.close()
                        null
                    } catch (error: Throwable) {
                        error
                    }
                }
                if (terminal) return@synchronized null
                val cancellation = CancellationException("Element activation was aborted.")
                terminal = true
                invalidateRenderers()?.let { cancellation.withSuppressed(it) }
                val closeNpcNow = npcAttachmentStagesSettled()
                npcCleanupDeferred = !closeNpcNow && npc != null
                terminalFailure =
                    closeOwnedResources(
                        npc.takeIf { closeNpcNow },
                        handles.filterNotNull(),
                        cancellation,
                    ) ?: cancellation
                future.completeExceptionally(terminalFailure)
                terminalFailure?.takeIf { it.suppressed.isNotEmpty() }
            }

        private fun resourceCompletedLocked() {
            pending--
            if (pending == 0) publishOrClose()
        }

        private fun failLocked(error: Throwable) {
            terminal = true
            invalidateRenderers()?.let { error.withSuppressed(it) }
            val closeNpcNow = npcAttachmentStagesSettled()
            npcCleanupDeferred = !closeNpcNow && npc != null
            terminalFailure =
                closeOwnedResources(npc.takeIf { closeNpcNow }, handles.filterNotNull(), error)
                    ?: error
            future.completeExceptionally(terminalFailure)
        }

        private fun npcAttachmentStagesSettled(): Boolean =
            pendingNpcResources == 0 ||
                (npcStages.isNotEmpty() && npcStages.all { it.toCompletableFuture().isDone })

        private fun publishOrClose() {
            if (terminal) return
            val completedHandles = handles.map { requireNotNull(it) }
            if (state.generation != generation || !lifetime.isCurrent()) {
                terminal = true
                val error = IllegalStateException("Activation generation is stale.")
                invalidateRenderers()?.let { error.withSuppressed(it) }
                terminalFailure = closeOwnedResources(npc, completedHandles, error) ?: error
                future.completeExceptionally(terminalFailure)
                return
            }
            try {
                completedHandles.forEachIndexed { index, handle ->
                    val animationState = state.animationFor(requests[index].context.partId)
                    animationState.animation?.let { animation ->
                        val elapsedMillis =
                            animationState.startedNanos?.let {
                                elapsedNanos(clock.nanoTime(), it) / 1_000_000
                            } ?: 0
                        handle.startAnimation(animation, elapsedMillis)
                    }
                }
            } catch (error: Throwable) {
                failed(error)
                return
            }
            terminal = true
            npc?.let { interactionIds[it.interaction.uuid] = state.element.id }
            val active =
                ActiveElement(
                    state.element.id,
                    generation,
                    completedHandles,
                    npc,
                    state.element as? Npc,
                    requests.map { it.context.transform },
                    requests.map { it.context.partId },
                    SceneRenderTransform(state.element.transform, null),
                    resourceDrain::beginOperation,
                    resourceDrain::completeOperation,
                    resourceDrain::isClosing,
                    schedule,
                    colliderChanged,
                    ::invalidateRenderers,
                )
            if (retainUntilClaimed) published = active
            future.complete(active)
        }
    }

    private class ResourceDrain {
        private val completion = CompletableFuture<Pair<Throwable?, Boolean>>()
        private var operations = 0
        private var closing = false
        private var closeMustFail = false

        fun beginOperation() = synchronized(this) { operations++ }

        fun trackCleanup(stage: CompletionStage<Void>) {
            // Keep registration itself owned: an inline completion must not seal the drain
            // before a malformed registration has had a chance to report its failure.
            beginOperation()
            beginOperation()
            val settled = java.util.concurrent.atomic.AtomicBoolean()
            try {
                stage.whenComplete { _, error ->
                    if (settled.compareAndSet(false, true))
                        completeOperation(error?.unwrap(), closeMustFail = error != null)
                }
            } catch (error: Throwable) {
                if (settled.compareAndSet(false, true))
                    completeOperation(error, closeMustFail = true)
                else {
                    beginOperation()
                    completeOperation(error, closeMustFail = true)
                }
            } finally {
                completeOperation()
            }
        }

        private var failure: Throwable? = null

        fun recordFailure(error: Throwable) =
            synchronized(this) { if (closing) failure = failure.withSuppressed(error) }

        fun completeOperation(error: Throwable? = null, closeMustFail: Boolean = false) {
            val outcome =
                synchronized(this) {
                    if ((closing || closeMustFail) && error != null) {
                        failure = failure.withSuppressed(error)
                    }
                    if (closeMustFail) this.closeMustFail = true
                    operations--
                    check(operations >= 0) { "Resource operation completed without ownership." }
                    if (closing && operations == 0) resultLocked() else null
                }
            if (outcome != null) completion.complete(outcome)
        }

        fun beginClose(): CompletionStage<Pair<Throwable?, Boolean>> {
            val outcome =
                synchronized(this) {
                    closing = true
                    if (operations == 0) resultLocked() else null
                }
            if (outcome != null) completion.complete(outcome)
            return completion
        }

        fun isClosing(): Boolean = synchronized(this) { closing }

        private fun resultLocked() = failure to closeMustFail
    }
}

private fun Throwable?.withSuppressed(secondary: Throwable): Throwable {
    val primary = this ?: return secondary
    if (secondary !== primary) primary.addSuppressed(secondary)
    return primary
}

private fun Throwable.unwrap(): Throwable = (this as? CompletionException)?.cause ?: this
