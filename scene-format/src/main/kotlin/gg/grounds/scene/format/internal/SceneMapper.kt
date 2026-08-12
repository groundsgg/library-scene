package gg.grounds.scene.format

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

internal object SceneMapper {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private val factory =
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(64)
                    .maxStringLength(65_536)
                    .maxNumberLength(128)
                    .build()
            )
            .build()
    private val mapper: JsonMapper =
        JsonMapper.builder(factory)
            .addModule(kotlinModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build()

    fun read(bytes: ByteArray): SceneWire {
        if (bytes.size > MAX_BYTES) {
            throw DecodeFailure("/", "LIMIT_EXCEEDED", "Scene JSON exceeds 16777216 bytes.")
        }
        validateUtf8(bytes)
        try {
            validateSingleValue(bytes)
            val node =
                mapper.readTree(bytes)
                    ?: throw DecodeFailure(
                        "/",
                        "MALFORMED_JSON",
                        "Scene JSON must contain one value.",
                    )
            return WireReader.readScene(node)
        } catch (failure: DecodeFailure) {
            throw failure
        } catch (_: StreamConstraintsException) {
            throw DecodeFailure("/", "LIMIT_EXCEEDED", "Scene JSON exceeds a parser limit.")
        } catch (_: JacksonException) {
            throw DecodeFailure("/", "MALFORMED_JSON", "Scene JSON is malformed.")
        } catch (_: Exception) {
            throw DecodeFailure("/", "MALFORMED_JSON", "Scene JSON could not be parsed.")
        }
    }

    private fun validateUtf8(bytes: ByteArray) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        } catch (_: CharacterCodingException) {
            throw DecodeFailure("/", "MALFORMED_UTF8", "Scene JSON is not valid UTF-8.")
        }
    }

    /**
     * Consumes Jackson's token stream once before tree binding. This makes duplicate and
     * trailing-value classification independent of exception wording while forcing all configured
     * token constraints.
     */
    private fun validateSingleValue(bytes: ByteArray) {
        mapper.createParser(bytes).use { parser ->
            val first =
                parser.nextToken()
                    ?: throw DecodeFailure(
                        "/",
                        "MALFORMED_JSON",
                        "Scene JSON must contain one value.",
                    )
            validateValue(parser, first, "/")
            if (parser.nextToken() != null) {
                throw DecodeFailure(
                    "/",
                    "TRAILING_TOKEN",
                    "Scene JSON must contain exactly one value.",
                )
            }
        }
    }

    private fun validateValue(parser: JsonParser, token: JsonToken, path: String) {
        when (token) {
            JsonToken.START_OBJECT -> validateObject(parser, path)
            JsonToken.START_ARRAY -> validateArray(parser, path)
            JsonToken.VALUE_STRING,
            JsonToken.VALUE_NUMBER_INT,
            JsonToken.VALUE_NUMBER_FLOAT -> {
                parser.string // Force Jackson to materialize and constrain the complete token.
            }
            JsonToken.VALUE_TRUE,
            JsonToken.VALUE_FALSE,
            JsonToken.VALUE_NULL -> Unit
            else -> throw DecodeFailure(path, "MALFORMED_JSON", "Expected a JSON value.")
        }
    }

    private fun validateObject(parser: JsonParser, path: String) {
        val names = mutableSetOf<String>()
        while (true) {
            when (val token = parser.nextToken()) {
                JsonToken.END_OBJECT -> return
                JsonToken.PROPERTY_NAME -> {
                    val name = parser.currentName()
                    parser
                        .string // Force field-name constraints as well as value-string constraints.
                    val fieldPath = childPath(path, name)
                    if (!names.add(name)) {
                        throw DecodeFailure(
                            fieldPath,
                            "DUPLICATE_FIELD",
                            "Object field is duplicated.",
                        )
                    }
                    val value =
                        parser.nextToken()
                            ?: throw DecodeFailure(
                                fieldPath,
                                "MALFORMED_JSON",
                                "Object field has no value.",
                            )
                    validateValue(parser, value, fieldPath)
                }
                else -> throw DecodeFailure(path, "MALFORMED_JSON", "Object is incomplete.")
            }
        }
    }

    private fun validateArray(parser: JsonParser, path: String) {
        var index = 0
        while (true) {
            val token =
                parser.nextToken()
                    ?: throw DecodeFailure(path, "MALFORMED_JSON", "Array is incomplete.")
            if (token == JsonToken.END_ARRAY) return
            validateValue(parser, token, childPath(path, index.toString()))
            index++
        }
    }
}

internal fun childPath(parent: String, segment: String): String {
    val escaped = segment.replace("~", "~0").replace("/", "~1")
    return if (parent == "/") "/$escaped" else "$parent/$escaped"
}

internal class DecodeFailure(val pointer: String, val code: String, override val message: String) :
    RuntimeException(message)
