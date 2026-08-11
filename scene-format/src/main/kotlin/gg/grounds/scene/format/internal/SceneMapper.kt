package gg.grounds.scene.format.internal

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

internal object SceneMapper {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private val mapper: JsonMapper = JsonMapper.builder(
        JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(65_536).maxNumberLength(128).build()).build(),
    ).addModule(kotlinModule()).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build()

    fun read(bytes: ByteArray): SceneWire {
        if (bytes.size > MAX_BYTES) throw DecodeFailure("/", "LIMIT_EXCEEDED", "Scene JSON exceeds 16777216 bytes")
        try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)) }
        catch (_: CharacterCodingException) { throw DecodeFailure("/", "MALFORMED_UTF8", "Scene JSON is not valid UTF-8.") }
        try {
            val node = mapper.readTree(bytes) ?: throw DecodeFailure("/", "MALFORMED_JSON", "Scene JSON must contain one value.")
            return WireReader.readScene(node)
        } catch (failure: DecodeFailure) { throw failure }
        catch (_: StreamConstraintsException) { throw DecodeFailure("/", "LIMIT_EXCEEDED", "Scene JSON exceeds a parser limit.") }
        catch (failure: Exception) { throw DecodeFailure("/", codeFor(failure), "Scene JSON is malformed.") }
    }

    private fun codeFor(failure: Exception): String = when {
        failure.message?.contains("Duplicate", ignoreCase = true) == true -> "DUPLICATE_FIELD"
        failure.message?.contains("Trailing token", ignoreCase = true) == true -> "TRAILING_TOKEN"
        else -> "MALFORMED_JSON"
    }
}

internal class DecodeFailure(val pointer: String, val code: String, override val message: String) : RuntimeException(message)
