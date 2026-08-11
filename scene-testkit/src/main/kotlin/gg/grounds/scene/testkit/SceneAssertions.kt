package gg.grounds.scene.testkit

import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneEncodeResult
import gg.grounds.scene.format.SceneJson
import gg.grounds.scene.format.SceneProblem
import gg.grounds.scene.format.SceneValidation
import java.security.MessageDigest

fun assertValidScene(scene: SceneDocument) {
    val problems = SceneValidation.validateIntrinsic(scene).problems
    if (problems.isNotEmpty()) throw AssertionError(renderProblems(problems))
}

fun assertCatalogCompatible(scene: SceneDocument, assets: AssetCatalog, actions: ActionCatalog) {
    val problems = SceneValidation.validateCatalogs(scene, assets, actions).problems
    if (problems.isNotEmpty()) throw AssertionError(renderProblems(problems))
}

fun assertCanonicalScene(scene: SceneDocument, expected: ByteArray) {
    val actual =
        when (val result = SceneJson.encode(scene)) {
            is SceneEncodeResult.Success -> result.bytes
            is SceneEncodeResult.Failure -> throw AssertionError(renderProblems(result.problems))
        }
    if (!actual.contentEquals(expected))
        throw AssertionError(renderByteDifference(expected, actual))
}

private fun renderProblems(problems: List<SceneProblem>): String = buildString {
    append("Scene validation failed:")
    problems.forEach { problem ->
        append("\n- path=").append(problem.path)
        append(" code=").append(problem.code)
        append(" identity=").append(problem.qualifiedIdentity)
        append(" message=").append(problem.message)
    }
}

private fun renderByteDifference(expected: ByteArray, actual: ByteArray): String = buildString {
    append("Canonical scene bytes differ:")
    append(" expected(size=")
        .append(expected.size)
        .append(", sha256=")
        .append(sha256(expected))
        .append(')')
    append(" actual(size=")
        .append(actual.size)
        .append(", sha256=")
        .append(sha256(actual))
        .append(')')
    append(" firstDifference=").append(firstDifference(expected, actual))
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun firstDifference(expected: ByteArray, actual: ByteArray): Int {
    val commonLength = minOf(expected.size, actual.size)
    for (index in 0 until commonLength) if (expected[index] != actual[index]) return index
    return commonLength
}
