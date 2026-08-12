package gg.grounds.scene.format

object SceneJson {
    fun encode(scene: SceneDocument): SceneEncodeResult {
        val problems = SceneValidation.validateIntrinsic(scene).problems
        if (problems.isNotEmpty()) return SceneEncodeResult.Failure(problems)
        return try {
            val bytes = CanonicalJson.write(WireMapping.toCanonicalWire(scene))
            SceneMapper.read(bytes)
            SceneEncodeResult.Success(bytes)
        } catch (_: RuntimeException) {
            SceneEncodeResult.Failure(
                listOf(
                    SceneProblem(
                        "/",
                        SceneProblemCode.ENCODING_FAILURE,
                        scene.id.value,
                        "Scene JSON could not be encoded.",
                    )
                )
            )
        }
    }

    fun decode(bytes: ByteArray): SceneDecodeResult =
        try {
            val scene = WireMapping.toDomain(SceneMapper.read(bytes))
            val problems = SceneValidation.validateIntrinsic(scene).problems
            if (problems.isEmpty()) SceneDecodeResult.Success(scene)
            else SceneDecodeResult.Failure(problems)
        } catch (failure: DecodeFailure) {
            SceneDecodeResult.Failure(
                listOf(
                    SceneProblem(
                        failure.pointer,
                        SceneProblemCode.valueOf(failure.code),
                        null,
                        failure.message,
                    )
                )
            )
        } catch (_: IllegalArgumentException) {
            SceneDecodeResult.Failure(
                listOf(
                    SceneProblem(
                        "/",
                        SceneProblemCode.MALFORMED_JSON,
                        null,
                        "Scene JSON does not satisfy the Scene schema.",
                    )
                )
            )
        } catch (_: Exception) {
            SceneDecodeResult.Failure(
                listOf(
                    SceneProblem(
                        "/",
                        SceneProblemCode.MALFORMED_JSON,
                        null,
                        "Scene JSON could not be decoded.",
                    )
                )
            )
        }
}
