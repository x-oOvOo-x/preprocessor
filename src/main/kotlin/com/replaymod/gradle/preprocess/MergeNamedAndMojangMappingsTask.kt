package com.replaymod.gradle.preprocess

import net.fabricmc.mappingio.MappingReader
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
import java.nio.file.Path

/**
 * Merges normal named mappings and Mojang mappings into one Tiny v2 mapping
 * tree containing:
 *
 *     official
 *     named
 *     mojang
 *
 * Both input mapping files must share the same official namespace.
 *
 * The `named` namespace comes from [namedMappings].
 * The `mojang` namespace comes from the named namespace of [mojangMappings].
 */
internal abstract class MergeNamedAndMojangMappingsTask :
    DefaultTask() {

    @get:InputFile
    abstract val namedMappings:
            RegularFileProperty

    @get:InputFile
    abstract val mojangMappings:
            RegularFileProperty

    @get:OutputFile
    abstract val output:
            RegularFileProperty

    @TaskAction
    fun merge() {
        val mappingTree =
            MemoryMappingTree()

        mappingTree.visitNamespaces(
            "official",
            listOf(
                "named",
                "mojang",
            ),
        )

        mappingTree.visitEnd()

        readMappings(
            namedMappings
                .get()
                .asFile
                .toPath(),
            mappingTree
                .withDstNs(
                    "named"
                )
                .withSrcNs(
                    "official"
                ),
        )

        readMappings(
            mojangMappings
                .get()
                .asFile
                .toPath(),
            mappingTree
                .withNsRename(
                    "named" to "mojang"
                )
                .withDstNs(
                    "named"
                )
                .withSrcNs(
                    "official"
                ),
        )

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
                    MappingNsCompleter(
                        Tiny2FileWriter(
                            writer,
                            false,
                        ),
                        null,
                    )
                )
            }
    }

    /**
     * Reads any mapping-io supported format into [visitor].
     */
    private fun readMappings(
        path: Path,
        visitor: MappingVisitor,
    ) {
        MappingReader.read(
            path,
            visitor,
        )
    }

    private fun MappingVisitor.withSrcNs(
        srcNs: String,
    ): MappingVisitor {
        return MappingSourceNsSwitch(
            this,
            srcNs,
        )
    }

    private fun MappingVisitor.withDstNs(
        vararg newDstNs: String,
    ): MappingVisitor {
        return MappingDstNsReorder(
            this,
            newDstNs.asList(),
        )
    }

    private fun MappingVisitor.withNsRename(
        vararg mapping:
        Pair<String, String>,
    ): MappingVisitor {
        return MappingNsRenamer(
            this,
            mapOf(
                *mapping
            ),
        )
    }
}
