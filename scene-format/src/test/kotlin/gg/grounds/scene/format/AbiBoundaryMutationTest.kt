package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_BRIDGE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.V25

class AbiBoundaryMutationTest {
    @Test
    fun `new public approved-package class reports its exact binary entry`() {
        assertUnexpectedClass("gg/grounds/scene/format/AddedPublic", ACC_PUBLIC)
    }

    @Test
    fun `constructor field method generic array exception and bounds report exact ABI signatures`() {
        assertUnexpectedAbi(
            mutate = {
                visitMethod(
                    ACC_PUBLIC,
                    "<init>",
                    "(Ltools/jackson/databind/ObjectMapper;)V",
                    null,
                    null,
                )
            },
            expected =
                "METHOD gg.grounds.scene.format.Decoy.<init>(Ltools/jackson/databind/ObjectMapper;)V access=public signature=- exceptions=[]",
        )
        assertUnexpectedAbi(
            mutate = {
                visitField(
                    ACC_PUBLIC,
                    "mapper",
                    "Ltools/jackson/databind/ObjectMapper;",
                    null,
                    null,
                )
            },
            expected =
                "FIELD gg.grounds.scene.format.Decoy.mapper Ltools/jackson/databind/ObjectMapper; access=public signature=-",
        )
        assertUnexpectedAbi(
            mutate = {
                visitMethod(
                    ACC_PUBLIC,
                    "mapper",
                    "()Ltools/jackson/databind/ObjectMapper;",
                    null,
                    null,
                )
            },
            expected =
                "METHOD gg.grounds.scene.format.Decoy.mapper()Ltools/jackson/databind/ObjectMapper; access=public signature=- exceptions=[]",
        )
        assertUnexpectedAbi(
            mutate = {
                visitMethod(
                    ACC_PUBLIC,
                    "mappers",
                    "()Ljava/util/List;",
                    "()Ljava/util/List<Ltools/jackson/databind/ObjectMapper;>;",
                    null,
                )
            },
            expected =
                "METHOD gg.grounds.scene.format.Decoy.mappers()Ljava/util/List; access=public signature=()Ljava/util/List<Ltools/jackson/databind/ObjectMapper;>; exceptions=[]",
        )
        assertUnexpectedAbi(
            mutate = {
                visitMethod(
                    ACC_PUBLIC,
                    "mappers",
                    "()[Ltools/jackson/databind/ObjectMapper;",
                    null,
                    null,
                )
            },
            expected =
                "METHOD gg.grounds.scene.format.Decoy.mappers()[Ltools/jackson/databind/ObjectMapper; access=public signature=- exceptions=[]",
        )
        assertUnexpectedAbi(
            mutate = {
                visitMethod(
                    ACC_PUBLIC,
                    "read",
                    "()V",
                    null,
                    arrayOf("tools/jackson/databind/JsonMappingException"),
                )
            },
            expected =
                "METHOD gg.grounds.scene.format.Decoy.read()V access=public signature=- exceptions=[tools.jackson.databind.JsonMappingException]",
        )
        assertUnexpectedAbi(
            classSignature = "<T:Ltools/jackson/databind/ObjectMapper;>Ljava/lang/Object;",
            expected =
                "CLASS gg.grounds.scene.format.Decoy access=public|super signature=<T:Ltools/jackson/databind/ObjectMapper;>Ljava/lang/Object; super=java.lang.Object interfaces=[]",
        )
        assertUnexpectedAbi(
            mutate = {
                visitMethod(
                    ACC_PUBLIC,
                    "bounded",
                    "()Ljava/lang/Object;",
                    "<T:Ltools/jackson/databind/ObjectMapper;>()TT;",
                    null,
                )
            },
            expected =
                "METHOD gg.grounds.scene.format.Decoy.bounded()Ljava/lang/Object; access=public signature=<T:Ltools/jackson/databind/ObjectMapper;>()TT; exceptions=[]",
        )
    }

    @Test
    fun `public internal synthetic bridge and compiler generated classes are not exempt`() {
        assertUnexpectedClass("gg/grounds/scene/format/internal/AddedInternal", ACC_PUBLIC)
        assertUnexpectedClass("gg/grounds/scene/format/AddedSynthetic", ACC_PUBLIC or ACC_SYNTHETIC)
        assertUnexpectedAbi(
            mutate = {
                visitMethod(ACC_PUBLIC or ACC_SYNTHETIC, "syntheticCall", "()V", null, null)
            },
            expected =
                "METHOD gg.grounds.scene.format.Decoy.syntheticCall()V access=public|synthetic signature=- exceptions=[]",
        )
        assertUnexpectedAbi(
            mutate = {
                visitMethod(
                    ACC_PUBLIC or ACC_BRIDGE or ACC_SYNTHETIC,
                    "value",
                    "()Ljava/lang/Object;",
                    null,
                    null,
                )
            },
            expected =
                "METHOD gg.grounds.scene.format.Decoy.value()Ljava/lang/Object; access=public|bridge|synthetic signature=- exceptions=[]",
        )
        assertUnexpectedClass("gg/grounds/scene/format/AddedInterface\$DefaultImpls", ACC_PUBLIC)
        assertUnexpectedClass("gg/grounds/scene/format/AddedEnum\$WhenMappings", ACC_PUBLIC)
    }

    @Test
    fun `companion helper DTO validator and comparator classes are exact inventory mutations`() {
        listOf(
                "gg/grounds/scene/format/Added\$Companion",
                "gg/grounds/scene/format/AddedHelper",
                "gg/grounds/scene/format/AddedDto",
                "gg/grounds/scene/format/AddedValidator",
                "gg/grounds/scene/format/AddedComparator",
            )
            .forEach { assertUnexpectedClass(it, ACC_PUBLIC) }
    }

    private fun assertUnexpectedClass(internalName: String, access: Int) {
        val failure =
            assertFailsWith<AssertionError> {
                AbiBoundary.assertExact(
                    mapOf("$internalName.class" to decoy(internalName, access = access)),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                )
            }
        assertEquals("Unexpected class entry: ${internalName.replace('/', '.')}", failure.message)
    }

    private fun assertUnexpectedAbi(
        classSignature: String? = null,
        mutate: ClassWriter.() -> Unit = {},
        expected: String,
    ) {
        val internalName = "gg/grounds/scene/format/Decoy"
        val expectedClass =
            "CLASS gg.grounds.scene.format.Decoy access=public|super signature=- super=java.lang.Object interfaces=[]"
        val failure =
            assertFailsWith<AssertionError> {
                AbiBoundary.assertExact(
                    mapOf(
                        "$internalName.class" to
                            decoy(internalName, classSignature = classSignature, mutate = mutate)
                    ),
                    setOf("gg.grounds.scene.format.Decoy"),
                    setOf("gg.grounds.scene.format.Decoy"),
                    setOf(expectedClass),
                )
            }
        assertEquals("Unexpected ABI: $expected", failure.message)
    }

    private fun decoy(
        internalName: String,
        access: Int = ACC_PUBLIC or ACC_SUPER,
        classSignature: String? = null,
        mutate: ClassWriter.() -> Unit = {},
    ): ByteArray =
        ClassWriter(0).run {
            visit(V25, access, internalName, classSignature, "java/lang/Object", null)
            mutate()
            visitEnd()
            toByteArray()
        }
}
