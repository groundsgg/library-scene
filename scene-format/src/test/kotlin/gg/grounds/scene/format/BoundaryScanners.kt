package gg.grounds.scene.format

import kotlin.Metadata
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

internal data class RuntimeComponent(val group: String, val name: String, val version: String) {
    val identity: String = "$group:$name"

    override fun toString(): String = "$identity:$version"

    companion object {
        fun parse(coordinate: String): RuntimeComponent {
            val pieces = coordinate.split(':')
            require(pieces.size == 3) { "Expected group:name:version, got $coordinate" }
            return RuntimeComponent(pieces[0], pieces[1], pieces[2])
        }
    }
}

internal object DependencyBoundary {
    private val forbiddenGroups =
        setOf(
            "io.papermc.paper",
            "org.bukkit",
            "net.minestom",
            "io.quarkus",
            "org.junit",
            "org.junit.jupiter",
            "org.junit.platform",
            "org.junit.vintage",
        )

    private val forbiddenComponents =
        setOf(
            "gg.grounds:service-maps",
            "gg.grounds:resource-pack-api",
            "gg.grounds:resource-pack-builder",
            "gg.grounds:resource-pack-testkit",
            "gg.grounds:scene-testkit",
            "junit:junit",
            "org.jetbrains.kotlin:kotlin-test",
            "org.jetbrains.kotlin:kotlin-test-annotations-common",
            "org.jetbrains.kotlin:kotlin-test-common",
            "org.jetbrains.kotlin:kotlin-test-junit",
            "org.jetbrains.kotlin:kotlin-test-junit5",
            "org.jetbrains.kotlin:kotlin-test-testng",
        )

    private val forbiddenJarPrefixes =
        listOf(
            "gg/grounds/servicemaps/",
            "gg/grounds/maps/",
            "gg/grounds/resourcepack/api/",
            "gg/grounds/resourcepack/builder/",
            "gg/grounds/resourcepack/testkit/",
            "gg/grounds/scene/testkit/",
            "io/papermc/",
            "org/bukkit/",
            "net/minestom/",
            "io/quarkus/",
            "org/junit/",
            "kotlin/test/",
        )

    fun assertNoForbiddenComponents(components: Iterable<RuntimeComponent>) {
        components
            .firstOrNull { it.group in forbiddenGroups || it.identity in forbiddenComponents }
            ?.let { throw AssertionError("Forbidden runtime component: $it") }
    }

    fun assertNoForbiddenJarEntries(entries: Set<String>) {
        entries.sorted().forEach { entry ->
            forbiddenJarPrefixes.firstOrNull(entry::startsWith)?.let { prefix ->
                throw AssertionError("Forbidden JAR entry: $entry (prefix $prefix)")
            }
        }
    }
}

internal object AbiBoundary {
    fun assertExact(
        classFiles: Map<String, ByteArray>,
        expectedClassEntries: Set<String>,
        expectedPublicApiClasses: Set<String>,
        expectedAbi: Set<String>,
    ) {
        val actualEntries = classEntries(classFiles)
        (actualEntries - expectedClassEntries).minOrNull()?.let {
            throw AssertionError("Unexpected class entry: $it")
        }
        (expectedClassEntries - actualEntries).minOrNull()?.let {
            throw AssertionError("Missing class entry: $it")
        }

        val actualAbi = abi(classFiles, expectedPublicApiClasses)
        (actualAbi - expectedAbi).minOrNull()?.let { throw AssertionError("Unexpected ABI: $it") }
        (expectedAbi - actualAbi).minOrNull()?.let { throw AssertionError("Missing ABI: $it") }
    }

    fun classEntries(classFiles: Map<String, ByteArray>): Set<String> =
        classFiles.keys.mapTo(sortedSetOf(), ::binaryName)

    fun abi(classFiles: Map<String, ByteArray>, publicApiClasses: Set<String>): Set<String> =
        publicApiClasses
            .flatMap { binaryName ->
                val entryName = binaryName.replace('.', '/') + ".class"
                val bytes =
                    classFiles[entryName]
                        ?: throw AssertionError("Missing public API class entry: $binaryName")
                abiOf(bytes)
            }
            .toSortedSet()

    fun kotlinPublicClasses(classFiles: Map<String, ByteArray>): Set<String> =
        classFiles.entries.mapNotNullTo(sortedSetOf()) { (entry, bytes) ->
            val binaryName = binaryName(entry)
            if (isKotlinPublic(binaryName, bytes)) binaryName else null
        }

    fun assertExactKotlinPublicClasses(
        classFiles: Map<String, ByteArray>,
        expectedPublicApiClasses: Set<String>,
    ) {
        val actual = kotlinPublicClasses(classFiles)
        (actual - expectedPublicApiClasses).minOrNull()?.let {
            throw AssertionError("Unexpected Kotlin-public class: $it")
        }
        (expectedPublicApiClasses - actual).minOrNull()?.let {
            throw AssertionError("Missing Kotlin-public class: $it")
        }
    }

    private fun binaryName(entry: String): String = entry.removeSuffix(".class").replace('/', '.')

    private fun abiOf(bytes: ByteArray): Set<String> {
        val lines = sortedSetOf<String>()
        ClassReader(bytes)
            .accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    private lateinit var owner: String

                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>,
                    ) {
                        owner = name.replace('/', '.')
                        lines +=
                            "CLASS $owner access=${accessText(access, CLASS_FLAGS)} signature=${signature ?: "-"} " +
                                "super=${superName?.replace('/', '.') ?: "-"} interfaces=${interfaces.map { it.replace('/', '.') }}"
                    }

                    override fun visitField(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        value: Any?,
                    ): FieldVisitor? {
                        if (isPublicOrProtected(access)) {
                            lines +=
                                "FIELD $owner.$name $descriptor access=${accessText(access, FIELD_FLAGS)} " +
                                    "signature=${signature ?: "-"}"
                        }
                        return null
                    }

                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        if (isPublicOrProtected(access)) {
                            lines +=
                                "METHOD $owner.$name$descriptor access=${accessText(access, METHOD_FLAGS)} " +
                                    "signature=${signature ?: "-"} exceptions=${exceptions.orEmpty().map { it.replace('/', '.') }}"
                        }
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        return lines
    }

    private fun isKotlinPublic(binaryName: String, bytes: ByteArray): Boolean {
        val metadata = readMetadata(bytes) ?: return false
        return when (val parsed = KotlinClassMetadata.readLenient(metadata)) {
            is KotlinClassMetadata.Class -> parsed.kmClass.visibility.isPublicApi()
            is KotlinClassMetadata.FileFacade -> parsed.kmPackage.hasPublicDeclaration()
            is KotlinClassMetadata.MultiFileClassPart -> parsed.kmPackage.hasPublicDeclaration()
            is KotlinClassMetadata.MultiFileClassFacade -> true
            is KotlinClassMetadata.SyntheticClass -> false
            is KotlinClassMetadata.Unknown ->
                throw AssertionError(
                    "Unsupported Kotlin metadata for $binaryName: " +
                        "kind=${metadata.kind} metadataVersion=${metadata.metadataVersion.contentToString()}"
                )
        }
    }

    private fun kotlin.metadata.KmPackage.hasPublicDeclaration(): Boolean =
        functions.any { it.visibility.isPublicApi() } ||
            properties.any { it.visibility.isPublicApi() } ||
            typeAliases.any { it.visibility.isPublicApi() }

    private fun Visibility.isPublicApi(): Boolean =
        this == Visibility.PUBLIC || this == Visibility.PROTECTED

    private fun readMetadata(bytes: ByteArray): Metadata? {
        var metadata: Metadata? = null
        ClassReader(bytes)
            .accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(
                        descriptor: String,
                        visible: Boolean,
                    ): AnnotationVisitor? {
                        if (descriptor != "Lkotlin/Metadata;") return null
                        return MetadataVisitor { metadata = it }
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        return metadata
    }

    private class MetadataVisitor(private val complete: (Metadata) -> Unit) :
        AnnotationVisitor(Opcodes.ASM9) {
        private var kind = 1
        private var metadataVersion = intArrayOf()
        private val data1 = mutableListOf<String>()
        private val data2 = mutableListOf<String>()
        private var extraString = ""
        private var packageName = ""
        private var extraInt = 0

        override fun visit(name: String, value: Any) {
            when (name) {
                "k" -> kind = value as Int
                "mv" -> metadataVersion = value as IntArray
                "xs" -> extraString = value as String
                "pn" -> packageName = value as String
                "xi" -> extraInt = value as Int
            }
        }

        override fun visitArray(name: String): AnnotationVisitor? =
            when (name) {
                "d1" -> stringArrayVisitor(data1)
                "d2" -> stringArrayVisitor(data2)
                else -> null
            }

        private fun stringArrayVisitor(target: MutableList<String>) =
            object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any) {
                    target += value as String
                }
            }

        override fun visitEnd() {
            complete(
                Metadata(
                    kind = kind,
                    metadataVersion = metadataVersion,
                    data1 = data1.toTypedArray(),
                    data2 = data2.toTypedArray(),
                    extraString = extraString,
                    packageName = packageName,
                    extraInt = extraInt,
                )
            )
        }
    }

    private fun isPublicOrProtected(access: Int): Boolean =
        access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0

    private fun accessText(access: Int, flags: List<Pair<Int, String>>): String =
        flags
            .filter { (mask) -> access and mask != 0 }
            .joinToString("|") { it.second }
            .ifEmpty { "package" }

    private val CLASS_FLAGS =
        listOf(
            Opcodes.ACC_PUBLIC to "public",
            Opcodes.ACC_PROTECTED to "protected",
            Opcodes.ACC_FINAL to "final",
            Opcodes.ACC_SUPER to "super",
            Opcodes.ACC_INTERFACE to "interface",
            Opcodes.ACC_ABSTRACT to "abstract",
            Opcodes.ACC_SYNTHETIC to "synthetic",
            Opcodes.ACC_ANNOTATION to "annotation",
            Opcodes.ACC_ENUM to "enum",
            Opcodes.ACC_RECORD to "record",
        )
    private val FIELD_FLAGS =
        listOf(
            Opcodes.ACC_PUBLIC to "public",
            Opcodes.ACC_PROTECTED to "protected",
            Opcodes.ACC_STATIC to "static",
            Opcodes.ACC_FINAL to "final",
            Opcodes.ACC_VOLATILE to "volatile",
            Opcodes.ACC_TRANSIENT to "transient",
            Opcodes.ACC_SYNTHETIC to "synthetic",
            Opcodes.ACC_ENUM to "enum",
        )
    private val METHOD_FLAGS =
        listOf(
            Opcodes.ACC_PUBLIC to "public",
            Opcodes.ACC_PROTECTED to "protected",
            Opcodes.ACC_STATIC to "static",
            Opcodes.ACC_FINAL to "final",
            Opcodes.ACC_SYNCHRONIZED to "synchronized",
            Opcodes.ACC_BRIDGE to "bridge",
            Opcodes.ACC_VARARGS to "varargs",
            Opcodes.ACC_NATIVE to "native",
            Opcodes.ACC_ABSTRACT to "abstract",
            Opcodes.ACC_STRICT to "strict",
            Opcodes.ACC_SYNTHETIC to "synthetic",
        )
}
