package gg.grounds.scene.format

import gg.grounds.scene.format.internal.DecodeFailure
import gg.grounds.scene.format.internal.SceneMapper
import gg.grounds.scene.format.internal.WireMapping

object SceneJson {
    fun decode(bytes: ByteArray): SceneDecodeResult = try {
        val scene = WireMapping.toDomain(SceneMapper.read(bytes))
        val problems = SceneValidation.validateIntrinsic(scene).problems
        if (problems.isEmpty()) SceneDecodeResult.Success(scene) else SceneDecodeResult.Failure(problems)
    } catch (failure: DecodeFailure) {
        SceneDecodeResult.Failure(listOf(SceneProblem(failure.pointer, SceneProblemCode.valueOf(failure.code), null, failure.message)))
    } catch (_: IllegalArgumentException) {
        SceneDecodeResult.Failure(listOf(SceneProblem("/", SceneProblemCode.MALFORMED_JSON, null, "Scene JSON does not satisfy the Scene schema.")))
    } catch (_: Exception) {
        SceneDecodeResult.Failure(listOf(SceneProblem("/", SceneProblemCode.MALFORMED_JSON, null, "Scene JSON could not be decoded.")))
    }
}
