package com.replaymod.gradle.preprocess

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.jar.JarInputStream

/**
 * Generates identity mappings from already-unobfuscated Minecraft jars.
 *
 * For an unobfuscated Minecraft version the class, field and method names in
 * the jar are already the names source code uses. There is therefore no
 * external mapping file from which Preprocessor can obtain a normal
 * official -> named relation.
 *
 * This task builds that relation directly from the classes in the Minecraft
 * jars:
 *
 *     official == named == mojang
 *
 * The generated Tiny v2 file can then participate in the same mapping
 * pipeline as conventional mapped Minecraft versions.
 */
internal abstract class GenerateIdentityMappingsFromMinecraftJars :
    DefaultTask() {

    /**
     * Minecraft jars belonging to the target version.
     *
     * Loom may expose more than one jar depending on the environment, so this
     * intentionally accepts a file collection rather than one file.
     */
    @get:InputFiles
    abstract val minecraftJars:
            ConfigurableFileCollection

    /**
     * Generated Tiny v2 identity mappings.
     */
    @get:OutputFile
    abstract val output:
            RegularFileProperty

    @TaskAction
    fun generate() {
        val mappingTree =
            MemoryMappingTree()

        mappingTree.visitNamespaces(
            "official",
            listOf(
                "named",
                "mojang",
            ),
        )

        val classVisitor =
            object :
                ClassVisitor(
                    Opcodes.ASM9,
                ) {

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    mappingTree.visitClass(
                        name
                    )

                    mappingTree.visitDstName(
                        MappedElementKind.CLASS,
                        0,
                        name,
                    )

                    mappingTree.visitDstName(
                        MappedElementKind.CLASS,
                        1,
                        name,
                    )

                    super.visit(
                        version,
                        access,
                        name,
                        signature,
                        superName,
                        interfaces,
                    )
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    mappingTree.visitField(
                        name,
                        descriptor,
                    )

                    mappingTree.visitDstName(
                        MappedElementKind.FIELD,
                        0,
                        name,
                    )

                    mappingTree.visitDstName(
                        MappedElementKind.FIELD,
                        1,
                        name,
                    )

                    return super.visitField(
                        access,
                        name,
                        descriptor,
                        signature,
                        value,
                    )
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    mappingTree.visitMethod(
                        name,
                        descriptor,
                    )

                    mappingTree.visitDstName(
                        MappedElementKind.METHOD,
                        0,
                        name,
                    )

                    mappingTree.visitDstName(
                        MappedElementKind.METHOD,
                        1,
                        name,
                    )

                    return super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions,
                    )
                }
            }

        for (
        minecraftJar
        in minecraftJars.files
        ) {
            minecraftJar
                .inputStream()
                .use {
                        rawInput ->

                    JarInputStream(
                        rawInput
                    ).use {
                            jarInput ->

                        while (true) {
                            val entry =
                                jarInput.nextEntry
                                    ?: break

                            if (
                                !entry.name
                                    .endsWith(
                                        ".class"
                                    )
                            ) {
                                continue
                            }

                            val classReader =
                                ClassReader(
                                    jarInput
                                )

                            classReader.accept(
                                classVisitor,
                                ClassReader.SKIP_CODE
                                        or ClassReader.SKIP_DEBUG
                                        or ClassReader.SKIP_FRAMES,
                            )
                        }
                    }
                }
        }

        mappingTree.visitEnd()

        val outputFile =
            output
                .get()
                .asFile

        outputFile
            .parentFile
            .mkdirs()

        outputFile
            .bufferedWriter()
            .use {
                    writer ->

                mappingTree.accept(
                    Tiny2FileWriter(
                        writer,
                        false,
                    )
                )
            }
    }
}
