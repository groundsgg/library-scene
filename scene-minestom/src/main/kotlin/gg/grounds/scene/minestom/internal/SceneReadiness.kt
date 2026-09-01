package gg.grounds.scene.minestom.internal

import gg.grounds.scene.format.*
import gg.grounds.scene.minestom.*
import java.util.Collections

internal data class RendererCapabilityKey(val asset: AssetKey, val kind: AssetKind)

internal data class SceneRuntimeCapabilities(
    val rendererFactories: Map<RendererCapabilityKey, SceneAssetRendererFactory>,
    val actionHandlers: Map<ActionKey, SceneActionHandler>,
)

internal data class SceneReadinessResult(
    val problems: List<SceneRuntimeProblem>,
    val capabilities: SceneRuntimeCapabilities?,
)

internal data class SceneReadinessRequest(
    val scene: SceneDocument,
    val assets: AssetCatalog,
    val actions: ActionCatalog,
    val renderers: SceneAssetRendererRegistry,
    val effects: SceneEffectSink,
    val actionRegistry: SceneActionRegistry,
    val identity: SceneRuntimeIdentity,
    val config: SceneRuntimeConfig,
)

internal object SceneReadiness {
    private val ordering =
        compareBy<SceneRuntimeProblem>(
            { it.path },
            { it.code.name },
            { it.elementId?.value.orEmpty() },
            { it.message },
        )

    fun check(request: SceneReadinessRequest): List<SceneRuntimeProblem> = prepare(request).problems

    fun prepare(request: SceneReadinessRequest): SceneReadinessResult {
        if (request.identity.sceneId != request.scene.id) {
            return SceneReadinessResult(
                listOf(
                    problem(
                        SceneRuntimeProblemCode.INVALID_CONFIG,
                        "identity/sceneId",
                        null,
                        "Runtime identity scene ID must match the Scene document ID.",
                    )
                ),
                SceneRuntimeCapabilities(
                    Collections.unmodifiableMap(LinkedHashMap()),
                    Collections.unmodifiableMap(LinkedHashMap()),
                ),
            )
        }
        val rendererFactories = linkedMapOf<RendererCapabilityKey, SceneAssetRendererFactory>()
        val resolvedRendererKeys = mutableSetOf<RendererCapabilityKey>()
        val actionHandlers = linkedMapOf<ActionKey, SceneActionHandler>()
        val resolvedActionKeys = mutableSetOf<ActionKey>()
        val problems =
            buildList {
                    SceneValidation.validateCatalogs(request.scene, request.assets, request.actions)
                        .problems
                        .forEach {
                            add(
                                problem(
                                    SceneRuntimeProblemCode.INVALID_SCENE,
                                    it.path,
                                    elementId(request.scene, it.path),
                                    it.message,
                                )
                            )
                        }
                    request.scene.elements.forEach { element ->
                        val path = "elements/${element.id.value}"
                        if (!element.visible) return@forEach
                        when (element) {
                            is Prop ->
                                renderer(
                                        request,
                                        rendererFactories,
                                        resolvedRendererKeys,
                                        element.asset,
                                        AssetKind.PROP,
                                        "$path/asset",
                                        element.id,
                                    )
                                    ?.let(::add)
                            is CompositeProp ->
                                element.parts.forEach { part ->
                                    renderer(
                                            request,
                                            rendererFactories,
                                            resolvedRendererKeys,
                                            part.asset,
                                            AssetKind.PROP,
                                            "$path/parts/${part.id.value}/asset",
                                            element.id,
                                        )
                                        ?.let(::add)
                                }
                            is Npc ->
                                renderer(
                                        request,
                                        rendererFactories,
                                        resolvedRendererKeys,
                                        element.body,
                                        AssetKind.NPC_BODY,
                                        "$path/body",
                                        element.id,
                                    )
                                    ?.let(::add)
                        }
                    }
                    forEachAction(request.scene) { action, path, elementId ->
                        when (action) {
                            is PlaySoundAction ->
                                if (!request.effects.supports(action.sound, AssetKind.SOUND))
                                    add(
                                        problem(
                                            SceneRuntimeProblemCode.MISSING_EFFECT,
                                            "$path/sound",
                                            elementId,
                                            "Effect sink does not support SOUND asset ${action.sound.value}.",
                                        )
                                    )
                            is EmitParticleAction ->
                                if (!request.effects.supports(action.particle, AssetKind.PARTICLE))
                                    add(
                                        problem(
                                            SceneRuntimeProblemCode.MISSING_EFFECT,
                                            "$path/particle",
                                            elementId,
                                            "Effect sink does not support PARTICLE asset ${action.particle.value}.",
                                        )
                                    )
                            is ApplicationAction -> {
                                if (resolvedActionKeys.add(action.key)) {
                                    request.actionRegistry.handlerFor(action.key)?.let {
                                        actionHandlers[action.key] = it
                                    }
                                }
                                if (action.key !in actionHandlers)
                                    add(
                                        problem(
                                            SceneRuntimeProblemCode.MISSING_ACTION_HANDLER,
                                            "$path/key",
                                            elementId,
                                            "No action handler for ${action.key.value}.",
                                        )
                                    )
                            }
                            else -> Unit
                        }
                    }
                }
                .sortedWith(ordering)
        val capabilities =
            if (problems.isEmpty()) {
                SceneRuntimeCapabilities(
                    Collections.unmodifiableMap(LinkedHashMap(rendererFactories)),
                    Collections.unmodifiableMap(LinkedHashMap(actionHandlers)),
                )
            } else {
                null
            }
        return SceneReadinessResult(problems, capabilities)
    }

    private fun renderer(
        request: SceneReadinessRequest,
        factories: MutableMap<RendererCapabilityKey, SceneAssetRendererFactory>,
        resolvedKeys: MutableSet<RendererCapabilityKey>,
        asset: AssetKey,
        kind: AssetKind,
        path: String,
        elementId: LocalId,
    ): SceneRuntimeProblem? {
        val key = RendererCapabilityKey(asset, kind)
        if (!resolvedKeys.add(key)) {
            return if (key in factories) null
            else
                problem(
                    SceneRuntimeProblemCode.MISSING_RENDERER,
                    path,
                    elementId,
                    "No renderer for ${kind.name} asset ${asset.value}.",
                )
        }
        val factory = request.renderers.rendererFor(asset, kind)
        return if (factory == null)
            problem(
                SceneRuntimeProblemCode.MISSING_RENDERER,
                path,
                elementId,
                "No renderer for ${kind.name} asset ${asset.value}.",
            )
        else {
            factories[key] = factory
            null
        }
    }

    private fun forEachAction(
        scene: SceneDocument,
        consumer: (SceneAction, String, LocalId) -> Unit,
    ) {
        scene.elements.filterIsInstance<Npc>().forEach { npc ->
            npc.bindings.forEachIndexed { bindingIndex, binding ->
                binding.actions.forEachIndexed { actionIndex, action ->
                    consumer(
                        action,
                        "elements/${npc.id.value}/bindings/$bindingIndex/actions/$actionIndex",
                        npc.id,
                    )
                }
            }
        }
    }

    private fun elementId(scene: SceneDocument, path: String): LocalId? {
        val id = path.removePrefix("elements/").substringBefore('/')
        return scene.elements.firstOrNull { it.id.value == id }?.id
    }

    private fun problem(
        code: SceneRuntimeProblemCode,
        path: String,
        elementId: LocalId?,
        message: String,
    ) = SceneRuntimeProblem(code, path, elementId, message)
}
