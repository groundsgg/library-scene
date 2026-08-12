package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_BRIDGE
import org.objectweb.asm.Opcodes.ACC_PRIVATE
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

    @Test
    fun `effective JVM reachability rejects every callable implementation member`() {
        val publicConstructor =
            decoy("gg/grounds/scene/format/internal/PublicHelper") {
                visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
            }
        val failure =
            assertFailsWith<AssertionError> {
                AbiBoundary.assertNoReachableImplementationSurface(
                    mapOf("PublicHelper.class" to publicConstructor),
                    emptySet(),
                )
            }
        assertEquals(
            "Externally reachable implementation member: " +
                "METHOD gg.grounds.scene.format.internal.PublicHelper.<init>()V signature=- exceptions=[]",
            failure.message,
        )

        AbiBoundary.assertNoReachableImplementationSurface(
            mapOf(
                "InertFacade.class" to decoy("gg/grounds/scene/format/InertFacade"),
                "PackageHelper.class" to
                    decoy("gg/grounds/scene/format/PackageHelper", access = ACC_SUPER) {
                        visitMethod(ACC_PUBLIC, "call", "()V", null, null)
                    },
                "PrivateNested.class" to privateNestedDecoy(),
            ),
            emptySet(),
        )
    }

    @Test
    fun `reachable Jackson signatures and synthetic methods have no exemption`() {
        val jackson =
            decoy("gg/grounds/scene/format/Approved") {
                visitMethod(
                    ACC_PUBLIC,
                    "mapper",
                    "()Ltools/jackson/databind/ObjectMapper;",
                    null,
                    null,
                )
            }
        val jacksonFailure =
            assertFailsWith<AssertionError> {
                AbiBoundary.assertNoReachableImplementationSurface(
                    mapOf("Approved.class" to jackson),
                    setOf("gg.grounds.scene.format.Approved"),
                )
            }
        assertEquals(
            "Reachable forbidden type: METHOD gg.grounds.scene.format.Approved.mapper()" +
                "Ltools/jackson/databind/ObjectMapper; signature=- exceptions=[]",
            jacksonFailure.message,
        )

        val synthetic =
            decoy("gg/grounds/scene/format/internal/SyntheticHelper") {
                visitMethod(ACC_PUBLIC or ACC_SYNTHETIC, "call", "()V", null, null)
            }
        val syntheticFailure =
            assertFailsWith<AssertionError> {
                AbiBoundary.assertNoReachableImplementationSurface(
                    mapOf("SyntheticHelper.class" to synthetic),
                    emptySet(),
                )
            }
        assertEquals(
            "Externally reachable implementation member: " +
                "METHOD gg.grounds.scene.format.internal.SyntheticHelper.call()V signature=- exceptions=[]",
            syntheticFailure.message,
        )
    }

    @Test
    fun `missing enclosing owner metadata fails closed`() {
        val nested =
            decoy("gg/grounds/scene/format/MissingOuter\$Nested") {
                visitInnerClass(
                    "gg/grounds/scene/format/MissingOuter\$Nested",
                    "gg/grounds/scene/format/MissingOuter",
                    "Nested",
                    ACC_PUBLIC,
                )
                visitMethod(ACC_PUBLIC, "call", "()V", null, null)
            }
        val failure =
            assertFailsWith<AssertionError> {
                AbiBoundary.assertNoReachableImplementationSurface(
                    mapOf("MissingOuter\$Nested.class" to nested),
                    emptySet(),
                )
            }
        assertEquals(
            "Externally reachable implementation member: " +
                "METHOD gg.grounds.scene.format.MissingOuter\$Nested.call()V " +
                "signature=- exceptions=[]",
            failure.message,
        )
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

    private fun privateNestedDecoy(): ByteArray =
        ClassWriter(0).run {
            val internalName = "gg/grounds/scene/format/PublicOuter\$PrivateNested"
            visit(V25, ACC_PUBLIC or ACC_SUPER, internalName, null, "java/lang/Object", null)
            visitInnerClass(
                internalName,
                "gg/grounds/scene/format/PublicOuter",
                "PrivateNested",
                ACC_PRIVATE,
            )
            visitMethod(ACC_PUBLIC, "call", "()V", null, null)
            visitEnd()
            toByteArray()
        }
}
