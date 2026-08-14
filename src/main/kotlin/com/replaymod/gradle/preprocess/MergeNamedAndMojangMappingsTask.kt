package com.replaymod.gradle.preprocess

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingDstNsReorder
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Combines normal named mappings and Mojang mappings into one Tiny v2 mapping
 * tree containing:
 *
 * official -> named
 * official -> mojang
 *
 * Input files may either be plain mapping files or Loom mapping jars.
 */
internal abstract class MergeNamedAndMojangMappingsTask : DefaultTask() {

    @get:InputFile
    abstract val namedMappings: RegularFileProperty

    @get:InputFile
    abstract val mojangMappings: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun merge() {
        val mappingTree = MemoryMappingTree()

        mappingTree.visitNamespaces(
            "official",
            listOf("named", "mojang")
        )
        mappingTree.visitEnd()

        readMappings(
            namedMappings.get().asFile.toPath(),
            mappingTree
                .withDstNs("named")
                .withSrcNs("official")
        )

        readMappings(
            mojangMappings.get().asFile.toPath(),
            mappingTree
                .withNsRename("named" to "mojang")
                .withDstNs("named")
                .withSrcNs("official")
        )

        val outputFile = output.get().asFile
        outputFile.parentFile.mkdirs()

        outputFile.bufferedWriter().use { writer ->
            mappingTree.accept(
                MappingNsCompleter(
                    Tiny2FileWriter(writer, false),
                    null
                )
            )
        }
    }

    private fun MappingVisitor.withSrcNs(srcNs: String): MappingVisitor =
        MappingSourceNsSwitch(this, srcNs)

    private fun MappingVisitor.withDstNs(vararg newDstNs: String): MappingVisitor =
        MappingDstNsReorder(this, newDstNs.asList())

    private fun MappingVisitor.withNsRename(
        vararg mapping: Pair<String, String>
    ): MappingVisitor =
        MappingNsRenamer(this, mapOf(*mapping))
}