package gg.grounds.scene.format

import gg.grounds.scene.format.internal.immutableListCopy

sealed interface SceneDecodeResult {
    data class Success(val scene: SceneDocument) : SceneDecodeResult

    @ConsistentCopyVisibility
    data class Failure
    private constructor(
        val problems: List<SceneProblem>,
        @Suppress("unused") private val canonical: Unit,
    ) : SceneDecodeResult {
        constructor(problems: List<SceneProblem>) : this(immutableProblems(problems), Unit)
    }
}

sealed interface SceneEncodeResult {
    class Success(bytes: ByteArray) : SceneEncodeResult {
        private val content: ByteArray = bytes.copyOf()
        val bytes: ByteArray
            get() = content.copyOf()
    }

    @ConsistentCopyVisibility
    data class Failure
    private constructor(
        val problems: List<SceneProblem>,
        @Suppress("unused") private val canonical: Unit,
    ) : SceneEncodeResult {
        constructor(problems: List<SceneProblem>) : this(immutableProblems(problems), Unit)
    }
}

@ConsistentCopyVisibility
data class SceneValidationResult
private constructor(
    val problems: List<SceneProblem>,
    @Suppress("unused") private val canonical: Unit,
) {
    constructor(
        problems: List<SceneProblem>
    ) : this(immutableProblems(problems, allowEmpty = true), Unit)

    val isValid: Boolean
        get() = problems.isEmpty()
}

private fun immutableProblems(
    problems: List<SceneProblem>,
    allowEmpty: Boolean = false,
): List<SceneProblem> {
    require(allowEmpty || problems.isNotEmpty()) { "Failure problems must not be empty." }
    return immutableListCopy(problems.sortedWith(SceneProblem.ORDERING))
}
