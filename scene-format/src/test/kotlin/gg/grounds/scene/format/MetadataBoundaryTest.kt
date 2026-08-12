package gg.grounds.scene.format

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.V25

class MetadataBoundaryTest {
    @Test
    fun `metadata kinds include public ABI and intentionally exclude non-public declarations`() {
        val classFiles =
            listOf(
                    PUBLIC_FACADE,
                    PRIVATE_FACADE,
                    PUBLIC_CLASS,
                    INTERNAL_CLASS,
                    MULTIFILE_FACADE,
                    PUBLIC_MULTIFILE_PART,
                    INTERNAL_MULTIFILE_PART,
                )
                .associate { binaryName -> entry(binaryName) to classBytes(binaryName) } +
                mapOf(entry(SYNTHETIC_CLASS) to metadataClass(SYNTHETIC_CLASS, kind = 3))

        val publicClasses = AbiBoundary.kotlinPublicClasses(classFiles)

        assertEquals(
            setOf(PUBLIC_FACADE, PUBLIC_CLASS, MULTIFILE_FACADE, PUBLIC_MULTIFILE_PART),
            publicClasses,
        )
        val abi = AbiBoundary.abi(classFiles, publicClasses)
        assertContains(
            abi,
            "CLASS $MULTIFILE_FACADE access=public|final|super signature=- super=java.lang.Object interfaces=[]",
        )
        assertContains(
            abi,
            "METHOD $MULTIFILE_FACADE.publicMultifileValue()I access=public|static|final signature=- exceptions=[]",
        )
        assertContains(
            abi,
            "METHOD $PUBLIC_FACADE.publicFacadeValue()Ljava/lang/String; access=public|static|final signature=- exceptions=[]",
        )
    }

    @Test
    fun `unknown metadata fails closed with exact binary version and kind`() {
        val failure =
            assertFailsWith<AssertionError> {
                AbiBoundary.kotlinPublicClasses(
                    mapOf(entry(UNKNOWN_CLASS) to metadataClass(UNKNOWN_CLASS, kind = 99))
                )
            }

        assertEquals(
            "Unsupported Kotlin metadata for $UNKNOWN_CLASS: kind=99 metadataVersion=[2, 2, 0]",
            failure.message,
        )
    }

    private fun classBytes(binaryName: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/${entry(binaryName)}")) {
                "Missing metadata fixture $binaryName"
            }
            .readAllBytes()

    private fun metadataClass(binaryName: String, kind: Int): ByteArray =
        ClassWriter(0).run {
            visit(
                V25,
                ACC_PUBLIC or ACC_SUPER,
                binaryName.replace('.', '/'),
                null,
                "java/lang/Object",
                null,
            )
            visitAnnotation("Lkotlin/Metadata;", true).run {
                visit("k", kind)
                visit("mv", intArrayOf(2, 2, 0))
                visit("xi", 0)
                visitEnd()
            }
            visitEnd()
            toByteArray()
        }

    private fun entry(binaryName: String): String = binaryName.replace('.', '/') + ".class"

    private companion object {
        const val PREFIX = "gg.grounds.scene.format.metadatafixtures."
        const val PUBLIC_FACADE = "${PREFIX}PublicFileFacadeFixture"
        const val PRIVATE_FACADE = "${PREFIX}PrivateFileFacadeFixture"
        const val PUBLIC_CLASS = "${PREFIX}PublicClassFixture"
        const val INTERNAL_CLASS = "${PREFIX}InternalClassFixture"
        const val MULTIFILE_FACADE = "${PREFIX}MetadataMultifileFacade"
        const val PUBLIC_MULTIFILE_PART =
            "${PREFIX}MetadataMultifileFacade__MetadataMultifilePublicKt"
        const val INTERNAL_MULTIFILE_PART =
            "${PREFIX}MetadataMultifileFacade__MetadataMultifileInternalKt"
        const val SYNTHETIC_CLASS = "${PREFIX}SyntheticMetadataFixture"
        const val UNKNOWN_CLASS = "${PREFIX}UnknownMetadataFixture"
    }
}
