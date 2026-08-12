package gg.grounds.scene.format

internal object IntrinsicValidator {
    private const val MAX_GROUPS = 4_096
    private const val MAX_ELEMENTS = 100_000
    private const val MAX_PARTS = 4_096
    private const val MAX_BINDINGS = 128
    private const val MAX_ACTIONS = 128
    private const val MAX_ARGUMENTS = 64

    fun validate(scene: SceneDocument): List<SceneProblem> = buildList {
        if (scene.schemaVersion != 1)
            add(
                problem(
                    "schemaVersion",
                    SceneProblemCode.UNSUPPORTED_SCHEMA_VERSION,
                    scene.id.value,
                    "Schema version must be 1.",
                )
            )
        if (scene.groups.size > MAX_GROUPS) add(limit("groups", scene.id.value))
        duplicates(scene.groups.map { it.id }, "groups", SceneProblemCode.DUPLICATE_GROUP_ID, this)
        if (scene.elements.size > MAX_ELEMENTS) add(limit("elements", scene.id.value))
        duplicates(
            scene.elements.map { it.id },
            "elements",
            SceneProblemCode.DUPLICATE_ELEMENT_ID,
            this,
        )
        val groupIds = scene.groups.map { it.id }.toSet()
        val elements = scene.elements.associateBy { it.id }
        scene.elements.forEach { element ->
            val path = "elements/${element.id.value}"
            val group = element.group
            if (group != null && group !in groupIds)
                add(
                    problem(
                        "$path/group",
                        SceneProblemCode.MISSING_GROUP,
                        group.value,
                        "Group does not exist.",
                    )
                )
            when (element) {
                is CompositeProp -> {
                    if (element.parts.isEmpty() || element.parts.size > MAX_PARTS)
                        add(limit("$path/parts", element.id.value))
                    duplicates(
                        element.parts.map { it.id },
                        "$path/parts",
                        SceneProblemCode.DUPLICATE_PART_ID,
                        this,
                    )
                }
                is Npc -> {
                    val identity = "${scene.id.value}#${element.id.value}"
                    element.label?.let { label ->
                        addAll(ComponentSafety.findProblems(label, "$path/label", identity))
                    }
                    if (element.bindings.size > MAX_BINDINGS)
                        add(limit("$path/bindings", element.id.value))
                    element.bindings.forEachIndexed { index, binding ->
                        validateBinding(
                            binding,
                            "$path/bindings/$index",
                            element,
                            identity,
                            elements,
                            this,
                        )
                    }
                }
                is Prop -> Unit
            }
        }
    }

    private fun validateBinding(
        binding: TriggerBinding,
        path: String,
        npc: Npc,
        identity: String,
        elements: Map<LocalId, SceneElement>,
        problems: MutableList<SceneProblem>,
    ) {
        if (binding.actions.size > MAX_ACTIONS) problems += limit("$path/actions", npc.id.value)
        if (
            (binding.trigger == SceneTrigger.PROXIMITY_ENTER ||
                binding.trigger == SceneTrigger.PROXIMITY_LEAVE) && npc.proximity == null
        ) {
            problems +=
                problem(
                    "$path/trigger",
                    SceneProblemCode.MISSING_PROXIMITY_SENSOR,
                    npc.id.value,
                    "Proximity trigger requires a proximity sensor.",
                )
        }
        binding.actions.forEachIndexed { index, action ->
            validateAction(action, "$path/actions/$index", identity, elements, problems)
        }
    }

    private fun validateAction(
        action: SceneAction,
        path: String,
        identity: String,
        elements: Map<LocalId, SceneElement>,
        problems: MutableList<SceneProblem>,
    ) {
        when (action) {
            is SendMessageAction ->
                problems += ComponentSafety.findProblems(action.message, "$path/message", identity)
            is SendActionBarAction ->
                problems += ComponentSafety.findProblems(action.message, "$path/message", identity)
            is ShowTitleAction -> {
                problems += ComponentSafety.findProblems(action.title, "$path/title", identity)
                problems +=
                    ComponentSafety.findProblems(action.subtitle, "$path/subtitle", identity)
            }
            else -> Unit
        }
        if (action is ApplicationAction && action.arguments.size > MAX_ARGUMENTS)
            problems += limit("$path/arguments", action.key.value)
        val target =
            when (action) {
                is StartAnimationAction -> action.target
                is StopAnimationAction -> action.target
                is SetViewerScaleAction -> action.target
                is SetViewerHighlightAction -> action.target
                is EmitParticleAction -> action.target
                else -> null
            } ?: return
        val element = elements[target.element]
        if (
            element == null ||
                (target.part != null &&
                    (element !is CompositeProp || element.parts.none { it.id == target.part }))
        ) {
            problems +=
                problem(
                    "$path/target",
                    SceneProblemCode.UNKNOWN_TARGET,
                    target.element.value,
                    "Action target does not exist.",
                )
        }
    }

    private fun duplicates(
        ids: List<LocalId>,
        path: String,
        code: SceneProblemCode,
        problems: MutableList<SceneProblem>,
    ) {
        ids.groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sortedBy { it.value }
            .forEach { id ->
                problems +=
                    problem(
                        "$path/${id.value}",
                        code,
                        id.value,
                        "Identifier is duplicated in this scope.",
                    )
            }
    }

    private fun limit(path: String, identity: String) =
        problem(path, SceneProblemCode.LIMIT_EXCEEDED, identity, "Collection limit exceeded.")

    private fun problem(path: String, code: SceneProblemCode, identity: String?, message: String) =
        SceneProblem(path, code, identity, message)
}
