import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.ow2.asm:asm:9.8") }
}

abstract class NormalizeJvmVisibility : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputRoots: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val approvedApi: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val internalInventory: RegularFileProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun normalize() {
        val approved = approvedApi.get().asFile.readLines().filter(String::isNotBlank).toSet()
        val internal =
            internalInventory.get().asFile.readLines().filter(String::isNotBlank).toSortedSet()
        val roots = inputRoots.files.filter { it.exists() }.sortedBy { it.absolutePath }
        val classFiles =
            roots
                .flatMap { root ->
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .map { file -> root to file }
                        .toList()
                }
                .associate { (root, file) ->
                    file
                        .relativeTo(root)
                        .invariantSeparatorsPath
                        .removeSuffix(".class")
                        .replace('/', '.') to (root to file)
                }
        val actualInternal = classFiles.keys - approved
        check(actualInternal == internal) {
            val added = (actualInternal - internal).sorted()
            val missing = (internal - actualInternal).sorted()
            "JVM-internal class inventory changed; added=$added missing=$missing"
        }

        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        roots.forEach { root ->
            root
                .walkTopDown()
                .filter { it.isFile }
                .forEach { input ->
                    val relative = input.relativeTo(root).invariantSeparatorsPath
                    val target = output.resolve(relative)
                    target.parentFile.mkdirs()
                    if (input.extension == "class") {
                        val binaryName = relative.removeSuffix(".class").replace('/', '.')
                        val bytes = input.readBytes()
                        target.writeBytes(
                            if (binaryName in internal) normalizeClass(bytes, internal) else bytes
                        )
                    } else {
                        Files.copy(
                            input.toPath(),
                            target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                }
        }
    }

    private fun normalizeClass(bytes: ByteArray, internal: Set<String>): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>,
                ) {
                    super.visit(
                        version,
                        packageAccess(access),
                        name,
                        signature,
                        superName,
                        interfaces,
                    )
                }

                override fun visitInnerClass(
                    name: String,
                    outerName: String?,
                    innerName: String?,
                    access: Int,
                ) {
                    val binaryName = name.replace('/', '.')
                    super.visitInnerClass(
                        name,
                        outerName,
                        innerName,
                        if (binaryName in internal) packageAccess(access) else access,
                    )
                }
            },
            0,
        )
        return writer.toByteArray()
    }

    private fun packageAccess(access: Int): Int =
        access and Opcodes.ACC_PUBLIC.inv() and Opcodes.ACC_PROTECTED.inv()
}

dependencies {
    api("net.kyori:adventure-api:4.21.0")
    implementation("net.kyori:adventure-text-serializer-gson:4.21.0")
    implementation("tools.jackson.core:jackson-databind:3.1.5")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.5")
    testImplementation(project(":scene-testkit"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.2.20")
    testImplementation("org.ow2.asm:asm:9.8")
}

val normalizeJvmVisibility =
    tasks.register<NormalizeJvmVisibility>("normalizeJvmVisibility") {
        dependsOn(tasks.named("classes"))
        inputRoots.from(sourceSets.main.get().output)
        approvedApi.set(
            layout.projectDirectory.file("src/test/resources/abi/kotlin-public-classes.txt")
        )
        internalInventory.set(layout.projectDirectory.file("jvm-internal-classes.txt"))
        outputDirectory.set(layout.buildDirectory.dir("normalized-jvm/main"))
    }

tasks.named<Jar>("jar") {
    dependsOn(normalizeJvmVisibility)
    val rawOutputRoots = sourceSets.main.get().output.files.map { it.toPath() }
    exclude { details -> rawOutputRoots.any { details.file.toPath().startsWith(it) } }
    from(normalizeJvmVisibility.flatMap { it.outputDirectory })
}

tasks.withType<Test>().configureEach {
    dependsOn(tasks.named("jar"))
    // Kotlin must compile main sources before their bytecode owners can be narrowed. The
    // publication boundary is therefore the normalized JAR, and runtime/ABI tests deliberately
    // replace Gradle's raw main output with that exact artifact.
    classpath = classpath.minus(sourceSets.main.get().output).plus(files(tasks.named<Jar>("jar")))
    systemProperty("sceneRepositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty(
        "sceneFormatRuntimeComponents",
        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.joinToString(
            "|"
        ) {
            "${it.moduleVersion.id.group}:${it.name}:${it.moduleVersion.id.version}"
        },
    )
    systemProperty(
        "sceneFormatJar",
        tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
    )
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        val version = requested.version?.split('.')?.map { it.toIntOrNull() ?: 0 }
        val belowFloor =
            version != null &&
                (version[0] < 3 ||
                    version[0] == 3 &&
                        (version.getOrElse(1) { 0 } < 1 ||
                            version.getOrElse(1) { 0 } == 1 && version.getOrElse(2) { 0 } < 4))
        if (requested.group?.startsWith("tools.jackson") == true && belowFloor) {
            throw GradleException("Jackson dependencies must be at least 3.1.4")
        }
    }
}
