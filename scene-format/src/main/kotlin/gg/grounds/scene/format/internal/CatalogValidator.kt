package gg.grounds.scene.format

internal object CatalogValidator {
    fun validate(
        scene: SceneDocument,
        assets: AssetCatalog,
        actions: ActionCatalog,
    ): List<SceneProblem> = buildList {
        if (
            scene.catalogs.assets.id != assets.id || scene.catalogs.assets.version != assets.version
        )
            add(
                problem(
                    "catalogs/assets",
                    SceneProblemCode.UNKNOWN_ASSET,
                    scene.catalogs.assets.id.value,
                    "Asset catalog pin does not match.",
                )
            )
        if (
            scene.catalogs.actions.id != actions.id ||
                scene.catalogs.actions.version != actions.version
        )
            add(
                problem(
                    "catalogs/actions",
                    SceneProblemCode.UNKNOWN_ACTION,
                    scene.catalogs.actions.id.value,
                    "Action catalog pin does not match.",
                )
            )
        val elements = scene.elements.associateBy { it.id }
        scene.elements.forEach { element ->
            val path = "elements/${element.id.value}"
            when (element) {
                is Prop -> validateAsset(element.asset, AssetKind.PROP, "$path/asset", assets, this)
                is CompositeProp ->
                    element.parts.forEach { part ->
                        validateAsset(
                            part.asset,
                            AssetKind.PROP,
                            "$path/parts/${part.id.value}/asset",
                            assets,
                            this,
                        )
                    }
                is Npc -> {
                    validateAsset(element.body, AssetKind.NPC_BODY, "$path/body", assets, this)
                    element.initialAnimation?.let {
                        validateAnimation(element.body, it, "$path/initialAnimation", assets, this)
                    }
                    element.bindings.forEachIndexed { bindingIndex, binding ->
                        binding.actions.forEachIndexed { actionIndex, action ->
                            validateAction(
                                action,
                                "$path/bindings/$bindingIndex/actions/$actionIndex",
                                elements,
                                assets,
                                actions,
                                this,
                            )
                        }
                    }
                }
            }
            if (element is Prop)
                element.initialAnimation?.let {
                    validateAnimation(element.asset, it, "$path/initialAnimation", assets, this)
                }
        }
    }

    private fun validateAction(
        action: SceneAction,
        path: String,
        elements: Map<LocalId, SceneElement>,
        assets: AssetCatalog,
        actions: ActionCatalog,
        problems: MutableList<SceneProblem>,
    ) {
        when (action) {
            is StartAnimationAction ->
                targetAsset(action.target, elements)?.let {
                    validateAnimation(it, action.animation, "$path/animation", assets, problems)
                }
            is StopAnimationAction ->
                action.animation?.let { animation ->
                    targetAsset(action.target, elements)?.let {
                        validateAnimation(it, animation, "$path/animation", assets, problems)
                    }
                }
            is PlaySoundAction ->
                validateAsset(action.sound, AssetKind.SOUND, "$path/sound", assets, problems)
            is EmitParticleAction ->
                validateAsset(
                    action.particle,
                    AssetKind.PARTICLE,
                    "$path/particle",
                    assets,
                    problems,
                )
            is ApplicationAction -> validateApplication(action, path, assets, actions, problems)
            else -> Unit
        }
    }

    private fun targetAsset(
        target: ElementTarget,
        elements: Map<LocalId, SceneElement>,
    ): AssetKey? =
        when (val element = elements[target.element]) {
            is Prop -> element.asset
            is Npc -> element.body
            is CompositeProp ->
                target.part?.let { part -> element.parts.firstOrNull { it.id == part }?.asset }
            null -> null
        }

    private fun validateApplication(
        action: ApplicationAction,
        path: String,
        assets: AssetCatalog,
        catalog: ActionCatalog,
        problems: MutableList<SceneProblem>,
    ) {
        val definition = catalog.actions[action.key]
        if (definition == null) {
            problems +=
                problem(
                    "$path/key",
                    SceneProblemCode.UNKNOWN_ACTION,
                    action.key.value,
                    "Action does not exist in the catalog.",
                )
            return
        }
        action.arguments.forEach { (id, value) ->
            val parameter = definition.parameters[id]
            val argumentPath = "$path/arguments/${id.value}"
            if (
                parameter == null || !argumentMatches(value, parameter.type, parameter.constraints)
            ) {
                problems +=
                    problem(
                        argumentPath,
                        SceneProblemCode.INVALID_ACTION_ARGUMENT,
                        id.value,
                        "Action argument is invalid.",
                    )
            }
            if (value is AssetArgument) {
                val asset = assets.assets[value.value]
                if (asset == null) {
                    problems +=
                        problem(
                            argumentPath,
                            SceneProblemCode.UNKNOWN_ASSET,
                            value.value.value,
                            "Asset does not exist in the catalog.",
                        )
                } else if (
                    parameter?.constraints is AssetConstraints &&
                        asset.kind != parameter.constraints.expectedKind
                ) {
                    problems +=
                        problem(
                            argumentPath,
                            SceneProblemCode.INVALID_ACTION_ARGUMENT,
                            id.value,
                            "Action argument is invalid.",
                        )
                }
            }
        }
        definition.parameters.values
            .filter { it.required && it.defaultValue == null && it.id !in action.arguments }
            .forEach { parameter ->
                problems +=
                    problem(
                        "$path/arguments/${parameter.id.value}",
                        SceneProblemCode.INVALID_ACTION_ARGUMENT,
                        parameter.id.value,
                        "Required action argument is missing.",
                    )
            }
    }

    private fun validateAsset(
        key: AssetKey,
        expected: AssetKind,
        path: String,
        catalog: AssetCatalog,
        problems: MutableList<SceneProblem>,
    ) {
        val asset = catalog.assets[key]
        when {
            asset == null ->
                problems +=
                    problem(
                        path,
                        SceneProblemCode.UNKNOWN_ASSET,
                        key.value,
                        "Asset does not exist in the catalog.",
                    )
            asset.kind != expected ->
                problems +=
                    problem(
                        path,
                        SceneProblemCode.WRONG_ASSET_KIND,
                        key.value,
                        "Asset kind does not match its role.",
                    )
        }
    }

    private fun validateAnimation(
        key: AssetKey,
        animation: LocalId,
        path: String,
        catalog: AssetCatalog,
        problems: MutableList<SceneProblem>,
    ) {
        if (catalog.assets[key]?.animations?.contains(animation) != true)
            problems +=
                problem(
                    path,
                    SceneProblemCode.UNKNOWN_ANIMATION,
                    animation.value,
                    "Animation is not exposed by the asset.",
                )
    }

    private fun problem(path: String, code: SceneProblemCode, identity: String?, message: String) =
        SceneProblem(path, code, identity, message)
}
