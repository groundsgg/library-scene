package gg.grounds.scene.format

data class SceneProblem(
    val path: String,
    val code: SceneProblemCode,
    val qualifiedIdentity: String?,
    val message: String,
) {
    companion object {
        val ORDERING: Comparator<SceneProblem> = Comparator { left, right ->
            val path = compareCodePoints(left.path, right.path)
            if (path != 0) {
                path
            } else {
                val code = left.code.name.compareTo(right.code.name)
                if (code != 0) code else compareCodePoints(left.message, right.message)
            }
        }
    }
}

enum class SceneProblemCode {
    MALFORMED_UTF8,
    MALFORMED_JSON,
    DUPLICATE_FIELD,
    UNKNOWN_FIELD,
    UNKNOWN_TYPE,
    TRAILING_TOKEN,
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_IDENTIFIER,
    DUPLICATE_ELEMENT_ID,
    DUPLICATE_GROUP_ID,
    DUPLICATE_PART_ID,
    NON_FINITE_TRANSFORM,
    INVALID_SCALE,
    INVALID_BOUNDS,
    MISSING_GROUP,
    MISSING_PROXIMITY_SENSOR,
    UNKNOWN_TARGET,
    UNKNOWN_ASSET,
    WRONG_ASSET_KIND,
    UNKNOWN_ANIMATION,
    UNKNOWN_ACTION,
    INVALID_ACTION_ARGUMENT,
    FORBIDDEN_TEXT_EVENT,
    LIMIT_EXCEEDED,
    ENCODING_FAILURE,
}

private fun compareCodePoints(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftCodePoint = left.codePointAt(leftIndex)
        val rightCodePoint = right.codePointAt(rightIndex)
        if (leftCodePoint != rightCodePoint) return leftCodePoint.compareTo(rightCodePoint)
        leftIndex += Character.charCount(leftCodePoint)
        rightIndex += Character.charCount(rightCodePoint)
    }
    return (left.length - leftIndex).compareTo(right.length - rightIndex)
}
