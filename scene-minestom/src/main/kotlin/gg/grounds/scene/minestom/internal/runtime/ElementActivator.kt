package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.CompositeProp
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.Prop
import gg.grounds.scene.format.SceneElement
import gg.grounds.scene.format.Vec3
import gg.grounds.scene.minestom.RenderedAssetHandle
import gg.grounds.scene.minestom.SceneAssetRenderContext
import gg.grounds.scene.minestom.SceneAssetRendererRegistry
import gg.grounds.scene.minestom.SceneClock
import gg.grounds.scene.minestom.SceneRenderTransform
import gg.grounds.scene.minestom.internal.geometry.affine
import gg.grounds.scene.minestom.internal.geometry.transformed
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import kotlin.math.max
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
}

internal class ElementActivator(
    private val instance: Instance,
    private val renderers: SceneAssetRendererRegistry,
    private val clock: SceneClock,
    private val schedule: (Runnable) -> Unit = Runnable::run,
) {
    private val interactionIds = mutableMapOf<UUID, LocalId>()

    fun activate(state: LogicalElementState): CompletionStage<ActiveElement> =
        startActivation(state, false).stage

    fun beginActivation(state: LogicalElementState): ElementActivation =
        startActivation(state, true)

    private fun startActivation(
        state: LogicalElementState,
        retainUntilClaimed: Boolean,
    ): ElementActivation {
        val generation = state.generation
        val requested = renderRequests(state.element)
        val npcElement = state.element as? Npc
        val activation =
            Activation(state, generation, requested.size, requested.size, retainUntilClaimed)

        npcElement?.let { npc ->
            try {
                val platformEntities = createNpcEntities(npc)
                if (activation.attach(platformEntities)) {
                    platformEntities.setInstanceStages(npc).forEach { stage ->
                        stage.whenComplete { _, error ->
                            activation.schedule {
                                if (error != null) activation.failed(error.unwrap())
                                else activation.resourceCompleted()
                            }
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
                        requireNotNull(renderers.rendererFor(request.context.asset, request.kind)) {
                            "No renderer is available for ${request.context.asset.value}."
                        }
                    factory.create(request.context)
                } catch (error: Throwable) {
                    activation.failed(error)
                    return@forEachIndexed
                }
            stage.whenComplete { handle, error ->
                activation.schedule {
                    if (error != null) activation.failed(error.unwrap())
                    else activation.handleCompleted(index, handle)
                }
            }
        }
        if (requested.isEmpty() && npcElement == null) activation.completed()
        return activation
    }

    fun deactivate(active: ActiveElement) {
        active.npcEntities?.interaction?.uuid?.let(interactionIds::remove)
        active.close()
    }

    fun interactionElementId(interactionId: UUID): LocalId? = interactionIds[interactionId]

    private fun renderRequests(element: SceneElement): List<RenderRequest> =
        when (element) {
            is Prop ->
                listOf(RenderRequest(AssetKind.PROP, context(element, null, element.asset, null)))
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
                    RenderRequest(AssetKind.NPC_BODY, context(element, null, element.body, null))
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
        var label: Entity? = null
        try {
            val transform = SceneRenderTransform(npc.transform, null)
            val bounds = npc.interactionBounds.transformed(transform.affine())
            (interaction.entityMeta as InteractionMeta).apply {
                width = max(bounds.max.x - bounds.min.x, bounds.max.z - bounds.min.z).toFloat()
                height = (bounds.max.y - bounds.min.y).toFloat()
                response = true
            }
            label =
                npc.label?.let { text ->
                    Entity(EntityType.TEXT_DISPLAY).also { entity ->
                        (entity.entityMeta as TextDisplayMeta).text = text
                    }
                }
            return NpcPlatformEntities(label, interaction)
        } catch (error: Throwable) {
            throw closeOwnedResources(NpcPlatformEntities(label, interaction), emptyList(), error)
                ?: error
        }
    }

    private fun NpcPlatformEntities.setInstanceStages(npc: Npc): List<CompletionStage<Void>> {
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
            label?.let {
                add(
                    it.setInstance(
                        instance,
                        transform.affine().transform(npc.labelOffset).asPoint(),
                    )
                )
            }
            add(interaction.setInstance(instance, interactionPosition))
        }
    }

    private fun NpcPlatformEntities.resourceCount() = if (label == null) 1 else 2

    private fun Vec3.asPoint() = Vec(x, y, z)

    private data class RenderRequest(val kind: AssetKind, val context: SceneAssetRenderContext)

    private inner class Activation(
        private val state: LogicalElementState,
        private val generation: Long,
        private var pending: Int,
        handleCount: Int,
        private val retainUntilClaimed: Boolean,
    ) : ElementActivation {
        private val future = CompletableFuture<ActiveElement>()
        override val stage: CompletionStage<ActiveElement>
            get() = future

        private val handles = arrayOfNulls<RenderedAssetHandle>(handleCount)
        private var npc: NpcPlatformEntities? = null
        private var terminal = false
        private var terminalFailure: Throwable? = null
        private var published: ActiveElement? = null

        fun handleCompleted(index: Int, handle: RenderedAssetHandle) {
            synchronized(this) {
                if (terminal) {
                    recordLateCleanup(closeOwnedResources(null, listOf(handle)))
                    return
                }
                handles[index] = handle
                resourceCompletedLocked()
            }
        }

        fun isOpen(): Boolean = synchronized(this) { !terminal }

        fun attach(platformEntities: NpcPlatformEntities): Boolean =
            synchronized(this) {
                if (terminal) {
                    recordLateCleanup(closeOwnedResources(platformEntities, emptyList()))
                    false
                } else {
                    npc = platformEntities
                    pending += platformEntities.resourceCount()
                    true
                }
            }

        fun resourceCompleted() = synchronized(this) { if (!terminal) resourceCompletedLocked() }

        fun completed() = synchronized(this) { if (!terminal) publishOrClose() }

        fun schedule(action: () -> Unit) {
            try {
                schedule(Runnable(action))
            } catch (error: Throwable) {
                failed(error)
            }
        }

        fun failed(error: Throwable) {
            synchronized(this) {
                if (terminal) return
                terminal = true
                terminalFailure = closeOwnedResources(npc, handles.filterNotNull(), error) ?: error
                future.completeExceptionally(terminalFailure)
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
                    return@synchronized closeOwnedResources(active.npcEntities, active.handles)
                }
                if (terminal) return@synchronized null
                val cancellation = CancellationException("Element activation was aborted.")
                terminal = true
                terminalFailure =
                    closeOwnedResources(npc, handles.filterNotNull(), cancellation) ?: cancellation
                future.completeExceptionally(terminalFailure)
                terminalFailure?.takeIf { it.suppressed.isNotEmpty() }
            }

        private fun resourceCompletedLocked() {
            pending--
            if (pending == 0) publishOrClose()
        }

        private fun publishOrClose() {
            if (terminal) return
            val completedHandles = handles.map { requireNotNull(it) }
            if (state.generation != generation) {
                terminal = true
                val error = IllegalStateException("Activation generation is stale.")
                terminalFailure = closeOwnedResources(npc, completedHandles, error) ?: error
                future.completeExceptionally(terminalFailure)
                return
            }
            try {
                state.animation.animation?.let { animation ->
                    val elapsedMillis =
                        state.animation.startedNanos?.let { (clock.nanoTime() - it) / 1_000_000 }
                            ?: 0
                    completedHandles.forEach { it.startAnimation(animation, elapsedMillis) }
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
                )
            if (retainUntilClaimed) published = active
            future.complete(active)
        }

        private fun recordLateCleanup(error: Throwable?) {
            if (error == null) return
            terminalFailure?.let { failure -> if (error !== failure) failure.addSuppressed(error) }
        }
    }
}

private fun Throwable.unwrap(): Throwable = (this as? CompletionException)?.cause ?: this
