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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.server.entity.metadata.other.InteractionMeta
import net.minestom.server.instance.Instance

internal class ElementActivator(
    private val instance: Instance,
    private val renderers: SceneAssetRendererRegistry,
    private val clock: SceneClock,
) {
    private val interactionIds = mutableMapOf<UUID, LocalId>()

    fun activate(state: LogicalElementState): CompletionStage<ActiveElement> {
        val generation = state.generation
        val requested = renderRequests(state.element)
        val npcElement = state.element as? Npc
        val npc = npcElement?.let(::createNpcEntities)
        val activation =
            Activation(
                state,
                generation,
                requested.size + (npc?.resourceCount() ?: 0),
                requested.size,
                npc,
            )

        requested.forEachIndexed { index, request ->
            if (!activation.isOpen()) return@forEachIndexed
            val factory = requireNotNull(renderers.rendererFor(request.context.asset, request.kind))
            val stage =
                try {
                    factory.create(request.context)
                } catch (error: Throwable) {
                    activation.failed(error)
                    return@forEachIndexed
                }
            stage.whenComplete { handle, error ->
                if (error != null) activation.failed(error.unwrap())
                else activation.handleCompleted(index, handle)
            }
        }
        if (activation.isOpen()) {
            (npcElement?.let { npc?.setInstanceStages(it) ?: emptyList() }).orEmpty().forEach {
                stage ->
                stage.whenComplete { _, error ->
                    if (error != null) activation.failed(error.unwrap())
                    else activation.resourceCompleted()
                }
            }
        }
        if (requested.isEmpty() && npc == null) activation.completed()
        return activation.future
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
        val transform = SceneRenderTransform(npc.transform, null)
        val bounds = npc.interactionBounds.transformed(transform.affine())
        val interaction = Entity(EntityType.INTERACTION)
        (interaction.entityMeta as InteractionMeta).apply {
            width = (bounds.max.x - bounds.min.x).toFloat()
            height = (bounds.max.y - bounds.min.y).toFloat()
            response = true
        }
        val label =
            npc.label?.let { text ->
                Entity(EntityType.TEXT_DISPLAY).also { entity ->
                    (entity.entityMeta as TextDisplayMeta).text = text
                }
            }
        return NpcPlatformEntities(label, interaction)
    }

    private fun NpcPlatformEntities.setInstanceStages(npc: Npc): List<CompletionStage<Void>> {
        val transform = SceneRenderTransform(npc.transform, null)
        val bounds = npc.interactionBounds.transformed(transform.affine())
        val center = midpoint(bounds.min, bounds.max).asPoint()
        return buildList {
            label?.let {
                add(
                    it.setInstance(
                        instance,
                        transform.affine().transform(npc.labelOffset).asPoint(),
                    )
                )
            }
            add(interaction.setInstance(instance, center))
        }
    }

    private fun NpcPlatformEntities.resourceCount() = if (label == null) 1 else 2

    private fun Vec3.asPoint() = Vec(x, y, z)

    private fun midpoint(first: Vec3, second: Vec3) =
        Vec3((first.x + second.x) / 2.0, (first.y + second.y) / 2.0, (first.z + second.z) / 2.0)

    private data class RenderRequest(val kind: AssetKind, val context: SceneAssetRenderContext)

    private inner class Activation(
        private val state: LogicalElementState,
        private val generation: Long,
        private var pending: Int,
        handleCount: Int,
        private val npc: NpcPlatformEntities?,
    ) {
        val future = CompletableFuture<ActiveElement>()
        private val handles = arrayOfNulls<RenderedAssetHandle>(handleCount)
        private var terminal = false

        fun handleCompleted(index: Int, handle: RenderedAssetHandle) {
            synchronized(this) {
                if (terminal) {
                    handle.close()
                    return
                }
                handles[index] = handle
                resourceCompletedLocked()
            }
        }

        fun isOpen(): Boolean = synchronized(this) { !terminal }

        fun resourceCompleted() = synchronized(this) { if (!terminal) resourceCompletedLocked() }

        fun completed() = synchronized(this) { if (!terminal) publishOrClose() }

        fun failed(error: Throwable) {
            synchronized(this) {
                if (terminal) return
                terminal = true
                closeResources()
                future.completeExceptionally(error)
            }
        }

        private fun resourceCompletedLocked() {
            pending--
            if (pending == 0) publishOrClose()
        }

        private fun publishOrClose() {
            if (terminal) return
            terminal = true
            val completedHandles = handles.map { requireNotNull(it) }
            if (state.generation != generation) {
                closeResources(completedHandles)
                future.completeExceptionally(
                    IllegalStateException("Activation generation is stale.")
                )
                return
            }
            state.animation.animation?.let { animation ->
                val elapsedMillis =
                    state.animation.startedNanos?.let { (clock.nanoTime() - it) / 1_000_000 } ?: 0
                completedHandles.forEach { it.startAnimation(animation, elapsedMillis) }
            }
            npc?.let { interactionIds[it.interaction.uuid] = state.element.id }
            future.complete(ActiveElement(state.element.id, generation, completedHandles, npc))
        }

        private fun closeResources(
            handlesToClose: List<RenderedAssetHandle> = handles.filterNotNull()
        ) {
            npc?.close()
            handlesToClose.asReversed().forEach(RenderedAssetHandle::close)
        }
    }
}

private fun Throwable.unwrap(): Throwable = (this as? CompletionException)?.cause ?: this
