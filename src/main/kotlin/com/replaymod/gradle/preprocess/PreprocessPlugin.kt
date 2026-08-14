package com.replaymod.gradle.preprocess

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.*
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.stream.Collectors

class PreprocessPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val parent = project.parent
        if (parent == null) {
            project.apply<RootPreprocessPlugin>()
            return
        }

        project.evaluationDependsOn(parent.path)

        val rootExtension = parent.extensions.getByType<RootPreprocessExtension>()
        val coreProjectFile = rootExtension.mainProjectFile.orNull?.let { parent.file(it) }
            ?: project.file(rootExtension.mainProjectFileRel.getOrElse("../mainProject"))
        val coreProject = coreProjectFile.readText().trim()

        if (coreProject !in parent.childProjects) {
            throw IllegalStateException("Configured main project `$coreProject` does not exist.")
        }

        val graph = rootExtension.getRootNode(coreProject)
            ?: throw IllegalStateException("Preprocess graph was not configured.")

        if (graph.findNode(coreProject) == null) {
            throw IllegalStateException("Preprocess graph does not contain main project `$coreProject`.")
        }

        val projectNode = graph.findNode(project.name)
            ?: throw IllegalStateException("Preprocess graph does not contain ${project.name}.")

        /*
         * Determine whether this mapped node borders an unobfuscated node.
         * Only those mapped projects need additional Mojang mappings.
         */
        val adjacentNodes = mutableListOf<ProjectGraphNode>()
        graph.findParent(projectNode)?.first?.let(adjacentNodes::add)
        projectNode.links.forEach { adjacentNodes.add(it.first) }

        if (projectNode.isObfuscated && adjacentNodes.any { !it.isObfuscated }) {
            ModernLoomMappingBridge.prepareMojangMappings(project, projectNode.mcVersion)
        }

        val mcVersion = projectNode.mcVersion
        project.extra["mcVersion"] = mcVersion

        val ext = project.extensions.create(
            "preprocess",
            PreprocessExtension::class,
            project.objects,
            mcVersion
        )

        val kotlin = project.plugins.hasPlugin("kotlin")

        /*
         * Core project reads directly from root /src.
         */
        if (coreProject == project.name) {
            project.the<SourceSetContainer>().configureEach {
                java.setSrcDirs(listOf(parent.file("src/$name/java")))
                resources.setSrcDirs(listOf(parent.file("src/$name/resources")))

                if (kotlin) {
                    withGroovyBuilder {
                        getProperty("kotlin") as SourceDirectorySet
                    }.setSrcDirs(
                        listOf(
                            parent.file("src/$name/kotlin"),
                            parent.file("src/$name/java")
                        )
                    )
                }
            }

            return
        }

        /*
         * Locate the version from which this project inherits its sources.
         */
        val inheritedLink = projectNode.links.find {
            it.first.findNode(coreProject) != null
        }

        val (inheritedNode, extraMappings) = inheritedLink
            ?: requireNotNull(graph.findParent(projectNode)) {
                "Cannot find a parent node for project node $projectNode"
            }

        val (mappingFile, mappingFileInverted) = extraMappings
        val reverseMappings = (inheritedLink != null) != mappingFileInverted
        val inherited = parent.evaluationDependsOn(inheritedNode.project)

        /*
         * Mojang mappings are needed only for the mapped side of a
         * mapped <-> unobfuscated transition.
         */
        val inheritedMojangMappings =
            if (inheritedNode.isObfuscated && !projectNode.isObfuscated) {
                ModernLoomMappingBridge.requireMojangMappings(inherited)
            } else {
                null
            }

        val projectMojangMappings =
            if (projectNode.isObfuscated && !inheritedNode.isObfuscated) {
                ModernLoomMappingBridge.requireMojangMappings(project)
            } else {
                null
            }

        /*
         * Configure source preprocessing.
         */
        project.the<SourceSetContainer>().configureEach {
            val inheritedSourceSet = inherited.the<SourceSetContainer>()[name]
            val cName = if (name == "main") "" else name.uppercaseFirstChar()

            val overwritesKotlin = project.file("src/$name/kotlin").also { it.mkdirs() }
            val overwritesJava = project.file("src/$name/java").also { it.mkdirs() }
            val overwriteResources = project.file("src/$name/resources").also { it.mkdirs() }

            val preprocessedRoot = project.layout.buildDirectory.dir("preprocessed/$name")
            val generatedKotlin = preprocessedRoot.dir("kotlin")
            val generatedJava = preprocessedRoot.dir("java")
            val generatedResources = preprocessedRoot.dir("resources")

            val preprocessCode = project.tasks.register<PreprocessTask>("preprocess${cName}Code") {
                inherited.tasks.findByPath("preprocess${cName}Code")?.let { dependsOn(it) }

                entry(
                    source = inherited.files(inheritedSourceSet.java.srcDirs),
                    overwrites = overwritesJava,
                    generated = generatedJava.get().asFile
                )

                if (kotlin) {
                    val inheritedKotlinDirs = inheritedSourceSet.withGroovyBuilder {
                        getProperty("kotlin") as SourceDirectorySet
                    }.srcDirs.filter { it.endsWith("kotlin") }

                    entry(
                        source = inherited.files(inheritedKotlinDirs),
                        overwrites = overwritesKotlin,
                        generated = generatedKotlin.get().asFile
                    )
                }

                jdkHome.set(
                    (inherited.tasks["compileJava"] as JavaCompile).javaCompiler.map {
                        it.metadata.installationPath
                    }
                )

                remappedjdkHome.set(
                    (project.tasks["compileJava"] as JavaCompile).javaCompiler.map {
                        it.metadata.installationPath
                    }
                )

                classpath = inherited.tasks[
                    "compile${cName}${if (kotlin) "Kotlin" else "Java"}"
                ].classpath

                remappedClasspath = project.tasks[
                    "compile${cName}${if (kotlin) "Kotlin" else "Java"}"
                ].classpath

                mapping = mappingFile
                reverseMapping = reverseMappings

                vars.convention(ext.vars)
                keywords.convention(ext.keywords)
                patternAnnotation.convention(ext.patternAnnotation)
                manageImports.convention(ext.manageImports)
                enableRemapMessageCollector.convention(ext.enableRemapMessageCollector)
            }

            val sourceJavaTask = project.tasks.findByName("source${name.uppercaseFirstChar()}Java")
            (sourceJavaTask ?: project.tasks["compile${cName}Java"]).dependsOn(preprocessCode)

            java.setSrcDirs(
                listOf(
                    overwritesJava,
                    preprocessCode.map { generatedJava }
                )
            )

            if (kotlin) {
                val kotlinConsumerTask =
                    project.tasks.findByName("source${name.uppercaseFirstChar()}Kotlin")
                        ?: project.tasks["compile${cName}Kotlin"]

                kotlinConsumerTask.dependsOn(preprocessCode)

                withGroovyBuilder {
                    getProperty("kotlin") as SourceDirectorySet
                }.setSrcDirs(
                    listOf(
                        overwritesKotlin,
                        preprocessCode.map { generatedKotlin },
                        overwritesJava,
                        preprocessCode.map { generatedJava }
                    )
                )
            }

            val preprocessResources =
                project.tasks.register<PreprocessTask>("preprocess${cName}Resources") {
                    inherited.tasks.findByPath("preprocess${cName}Resources")?.let {
                        dependsOn(it)
                    }

                    entry(
                        source = inherited.files(inheritedSourceSet.resources.srcDirs),
                        overwrites = overwriteResources,
                        generated = generatedResources.get().asFile
                    )

                    vars.convention(ext.vars)
                    keywords.convention(ext.keywords)
                    patternAnnotation.convention(ext.patternAnnotation)
                    manageImports.convention(ext.manageImports)
                }

            project.tasks["process${cName}Resources"].dependsOn(preprocessResources)

            resources.setSrcDirs(
                listOf(
                    overwriteResources,
                    preprocessResources.map { generatedResources }
                )
            )

            if (mappingFile != null && name == "main") {
                project.tasks.register<CleanupUnnecessaryMappingsTask>(
                    "cleanupUnnecessaryMappings"
                ) {
                    task.set(preprocessCode)
                    this.mappingFile.set(mappingFile)
                }
            }
        }

        /*
         * Mapping pipeline.
         */
        project.afterEvaluate {
            if ("genSrgs" in project.tasks.names || "createMcpToSrg" in project.tasks.names) {
                configureForgeMappings(
                    project = project,
                    inherited = inherited,
                    projectNode = projectNode,
                    inheritedNode = inheritedNode,
                    rootExtension = rootExtension
                )

                return@afterEvaluate
            }

            configureLoomMappings(
                project = project,
                inherited = inherited,
                projectNode = projectNode,
                inheritedNode = inheritedNode,
                rootExtension = rootExtension,
                projectMojangMappings = projectMojangMappings,
                inheritedMojangMappings = inheritedMojangMappings
            )
        }

        /*
         * Switch canonical source version.
         */
        project.tasks.register<Copy>("setCoreVersion") {
            outputs.upToDateWhen { false }

            from(project.file("src"))
            from(project.layout.buildDirectory.dir("preprocessed"))
            into(project.parent!!.layout.projectDirectory.dir("src"))

            project.the<SourceSetContainer>().all {
                val cName = if (name == "main") "" else name.uppercaseFirstChar()
                dependsOn(project.tasks.named("preprocess${cName}Code"))
                dependsOn(project.tasks.named("preprocess${cName}Resources"))
            }

            doFirst {
                fun preserveOverwrites(
                    currentProject: Project,
                    toBePreserved: List<Path>?
                ) {
                    val overwrites = currentProject.file("src").toPath()

                    val overwritten = overwrites.toFile()
                        .walk()
                        .filter { it.isFile }
                        .map { overwrites.relativize(it.toPath()) }
                        .toList()

                    if (toBePreserved != null) {
                        val source =
                            if (currentProject.name == coreProject) {
                                currentProject.parent!!.file("src").toPath()
                            } else {
                                currentProject.layout.buildDirectory
                                    .dir("preprocessed")
                                    .get()
                                    .asFile
                                    .toPath()
                            }

                        currentProject.delete(overwrites)

                        toBePreserved.forEach { name ->
                            currentProject.copy {
                                from(source.resolve(name))
                                into(overwrites.resolve(name).parent)
                            }
                        }
                    }

                    if (currentProject.name != coreProject) {
                        val node = graph.findNode(currentProject.name)!!

                        val nextLink = node.links.find {
                            it.first.findNode(coreProject) != null
                        }

                        val (nextNode, _) = nextLink ?: graph.findParent(node)!!
                        val nextProject = parent.project(nextNode.project)

                        preserveOverwrites(nextProject, overwritten)
                    }
                }

                preserveOverwrites(project, null)
            }

            doLast {
                val overwrites = project.file("src")
                project.delete(overwrites)
                project.mkdir(overwrites)
            }

            doLast {
                coreProjectFile.writeText(project.name)
            }
        }
    }

    private fun configureLoomMappings(
        project: Project,
        inherited: Project,
        projectNode: ProjectGraphNode,
        inheritedNode: ProjectGraphNode,
        rootExtension: RootPreprocessExtension,
        projectMojangMappings: org.gradle.api.artifacts.Configuration?,
        inheritedMojangMappings: org.gradle.api.artifacts.Configuration?
    ) {
        val projectSrgMappings =
            if (projectNode.isObfuscated) project.tinyMappingsWithSrg else null
        val inheritedSrgMappings =
            if (inheritedNode.isObfuscated) inherited.tinyMappingsWithSrg else null

        val projectTinyMappings =
            if (projectNode.isObfuscated) project.tinyMappings else null
        val inheritedTinyMappings =
            if (inheritedNode.isObfuscated) inherited.tinyMappings else null

        val sourceIdentityTask =
            if (!inheritedNode.isObfuscated) {
                project.tasks.register<GenerateIdentityMappingsFromMinecraftJars>(
                    "generateIdentityMappingsFromSourceMinecraftJars"
                ) {
                    minecraftJars.from(
                        ModernLoomMappingBridge.namedMinecraftJars(inherited)
                    )

                    output.set(
                        project.layout.buildDirectory.file(
                            "preprocessor/mappings/source-identity.tiny"
                        )
                    )
                }
            } else {
                null
            }

        val destinationIdentityTask =
            if (!projectNode.isObfuscated) {
                project.tasks.register<GenerateIdentityMappingsFromMinecraftJars>(
                    "generateIdentityMappingsFromDestinationMinecraftJars"
                ) {
                    minecraftJars.from(
                        ModernLoomMappingBridge.namedMinecraftJars(project)
                    )

                    output.set(
                        project.layout.buildDirectory.file(
                            "preprocessor/mappings/destination-identity.tiny"
                        )
                    )
                }
            } else {
                null
            }

        val sourceMergedTask =
            if (inheritedNode.isObfuscated && !projectNode.isObfuscated) {
                project.tasks.register<MergeNamedAndMojangMappingsTask>(
                    "mergeSourceNamedAndMojangMappings"
                ) {
                    namedMappings.fileValue(requireNotNull(inheritedTinyMappings))

                    mojangMappings.fileProvider(
                        project.provider {
                            requireNotNull(inheritedMojangMappings).singleFile
                        }
                    )

                    output.set(
                        project.layout.buildDirectory.file(
                            "preprocessor/mappings/source-named-mojang.tiny"
                        )
                    )
                }
            } else {
                null
            }

        val destinationMergedTask =
            if (projectNode.isObfuscated && !inheritedNode.isObfuscated) {
                project.tasks.register<MergeNamedAndMojangMappingsTask>(
                    "mergeDestinationNamedAndMojangMappings"
                ) {
                    namedMappings.fileValue(requireNotNull(projectTinyMappings))

                    mojangMappings.fileProvider(
                        project.provider {
                            requireNotNull(projectMojangMappings).singleFile
                        }
                    )

                    output.set(
                        project.layout.buildDirectory.file(
                            "preprocessor/mappings/destination-named-mojang.tiny"
                        )
                    )
                }
            } else {
                null
            }

        project.tasks.withType<PreprocessTask>().configureEach {
            when {
                /*
                 * unobfuscated -> unobfuscated
                 */
                !inheritedNode.isObfuscated && !projectNode.isObfuscated -> {
                    val source = requireNotNull(sourceIdentityTask)
                    val destination = requireNotNull(destinationIdentityTask)

                    dependsOn(source, destination)

                    sourceMappings = source.get().output.get().asFile
                    destinationMappings = destination.get().output.get().asFile
                    intermediateMappingsName.set("mojang")
                }

                /*
                 * mapped -> unobfuscated
                 *
                 * DDS: 1.21.11 -> 26.1.2
                 */
                inheritedNode.isObfuscated && !projectNode.isObfuscated -> {
                    val source = requireNotNull(sourceMergedTask)
                    val destination = requireNotNull(destinationIdentityTask)

                    dependsOn(source, destination)

                    sourceMappings = source.get().output.get().asFile
                    destinationMappings = destination.get().output.get().asFile
                    intermediateMappingsName.set("mojang")
                }

                /*
                 * unobfuscated -> mapped
                 */
                !inheritedNode.isObfuscated && projectNode.isObfuscated -> {
                    val source = requireNotNull(sourceIdentityTask)
                    val destination = requireNotNull(destinationMergedTask)

                    dependsOn(source, destination)

                    sourceMappings = source.get().output.get().asFile
                    destinationMappings = destination.get().output.get().asFile
                    intermediateMappingsName.set("mojang")
                }

                /*
                 * mapped -> mapped
                 */
                (inheritedSrgMappings != null) == (projectSrgMappings != null) -> {
                    sourceMappings = inheritedSrgMappings
                        ?: requireNotNull(inheritedTinyMappings)

                    destinationMappings = projectSrgMappings
                        ?: requireNotNull(projectTinyMappings)

                    intermediateMappingsName.set(
                        if (projectSrgMappings != null) "srg" else "intermediary"
                    )
                }

                /*
                 * Same MC version but different mapping representation.
                 */
                inheritedNode.mcVersion == projectNode.mcVersion -> {
                    sourceMappings = requireNotNull(inheritedTinyMappings)
                    destinationMappings = requireNotNull(projectTinyMappings)
                    intermediateMappingsName.set("official")
                }

                else -> {
                    throw IllegalStateException(
                        "Failed to find mappings from $inherited to $project."
                    )
                }
            }

            strictExtraMappings.convention(
                rootExtension.strictExtraMappings.orElse(false)
            )
        }

        if (!rootExtension.strictExtraMappings.isPresent) {
            project.logger.warn(
                "Legacy extra mappings are deprecated. " +
                        "Please consider enabling strict extra mappings via " +
                        "`preprocess.strictExtraMappings.set(true)` in your root project. " +
                        "You may suppress this message by explicitly setting it to `false`."
            )
        }
    }

    private fun configureForgeMappings(
        project: Project,
        inherited: Project,
        projectNode: ProjectGraphNode,
        inheritedNode: ProjectGraphNode,
        rootExtension: RootPreprocessExtension
    ) {
        project.logger.warn(
            "ForgeGradle compatibility in Preprocessor is deprecated. " +
                    "Consider switching to architectury-loom (or essential-loom for FG2)."
        )

        if (rootExtension.strictExtraMappings.getOrElse(false)) {
            throw UnsupportedOperationException(
                "Strict mappings are only supported with Loom."
            )
        }

        val prepareTaskName = "prepareMappingsForPreprocessor"
        val prepareSourceTaskName = "prepareSourceMappingsForPreprocessor"
        val prepareDestTaskName = "prepareDestMappingsForPreprocessor"

        val projectIntermediaryMappings = project.intermediaryMappings
        val inheritedIntermediaryMappings = inherited.intermediaryMappings
        val projectNotchMappings = project.notchMappings
        val inheritedNotchMappings = inherited.notchMappings

        val sourceSrg = project.layout.buildDirectory.get().asFile
            .resolve(prepareTaskName)
            .resolve("source.srg")

        val destinationSrg = project.layout.buildDirectory.get().asFile
            .resolve(prepareTaskName)
            .resolve("destination.srg")

        val (prepareSourceTask, prepareDestTask) =
            if (inheritedIntermediaryMappings.type == projectIntermediaryMappings.type) {
                Pair(
                    inherited.bakeNamedToIntermediaryMappings(
                        prepareSourceTaskName,
                        inheritedIntermediaryMappings,
                        sourceSrg
                    ),
                    project.bakeNamedToIntermediaryMappings(
                        prepareDestTaskName,
                        projectIntermediaryMappings,
                        destinationSrg
                    )
                )
            } else if (
                inheritedNotchMappings != null &&
                projectNotchMappings != null &&
                inheritedNode.mcVersion == projectNode.mcVersion
            ) {
                Pair(
                    inherited.bakeNamedToOfficialMappings(
                        prepareSourceTaskName,
                        inheritedNotchMappings,
                        inheritedIntermediaryMappings,
                        sourceSrg
                    ),
                    project.bakeNamedToOfficialMappings(
                        prepareDestTaskName,
                        projectNotchMappings,
                        projectIntermediaryMappings,
                        destinationSrg
                    )
                )
            } else {
                throw IllegalStateException(
                    "Failed to find mappings from $inherited to $project."
                )
            }

        project.tasks.withType<PreprocessTask>().configureEach {
            sourceMappings = sourceSrg
            destinationMappings = destinationSrg
            dependsOn(prepareSourceTask)
            dependsOn(prepareDestTask)
        }
    }
}

internal class MappingsFile(
    @Input val type: String,
    @Input val format: String,
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    val file: File
)

private fun Mappings.toFile() = MappingsFile(type, format, file)

@CacheableTask
internal abstract class BakeNamedToIntermediaryMappings : DefaultTask() {

    @get:Nested
    abstract val mappings: Property<MappingsFile>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun prepare() {
        val mappings = mappings.get()

        val mapping =
            if (mappings.format == "tiny") {
                val tiny = MemoryMappingTree().also {
                    MappingReader.read(mappings.file.toPath(), it)
                }

                TinyReader(
                    tiny,
                    "named",
                    if (mappings.type == "searge") "srg" else "intermediary"
                ).read()
            } else {
                readMappings(mappings.format, mappings.file.toPath())
            }

        MappingFormats.SRG.write(mapping, output.get().asFile.toPath())
    }
}

@CacheableTask
internal abstract class BakeNamedToOfficialMappings : DefaultTask() {

    @get:Nested
    abstract val mappings: Property<MappingsFile>

    @get:Nested
    @get:Optional
    abstract val namedToIntermediaryMappings: Property<MappingsFile>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun prepare() {
        val mappings = mappings.get()

        val mapping =
            if (mappings.format == "tiny") {
                val tiny = MemoryMappingTree().also {
                    MappingReader.read(mappings.file.toPath(), it)
                }

                TinyReader(tiny, "named", "official").read()
            } else {
                val intermediaryMappings = namedToIntermediaryMappings.get()

                val intermediarySet = readMappings(
                    intermediaryMappings.format,
                    intermediaryMappings.file.toPath()
                )

                val officialSet = readMappings(
                    mappings.format,
                    mappings.file.toPath()
                )

                officialSet.join(intermediarySet.reverse()).reverse()
            }

        MappingFormats.SRG.write(mapping, output.get().asFile.toPath())
    }
}

private fun Project.bakeNamedToIntermediaryMappings(
    name: String,
    namedToIntermediaryMappings: Mappings,
    destination: File
): TaskProvider<BakeNamedToIntermediaryMappings> {
    val task = tasks.register(name, BakeNamedToIntermediaryMappings::class)

    task.configure {
        dependsOn(namedToIntermediaryMappings.tasks)
        mappings.set(namedToIntermediaryMappings.toFile())
        output.set(destination)
    }

    return task
}

private fun Project.bakeNamedToOfficialMappings(
    name: String,
    mappings: Mappings,
    namedToIntermediaryMappings: Mappings,
    destination: File
): TaskProvider<BakeNamedToOfficialMappings> {
    val task = tasks.register(name, BakeNamedToOfficialMappings::class)

    task.configure {
        dependsOn(mappings.tasks, namedToIntermediaryMappings.tasks)

        this.mappings.set(mappings.toFile())
        this.namedToIntermediaryMappings.set(namedToIntermediaryMappings.toFile())
        output.set(destination)
    }

    return task
}

private fun readMappings(format: String, path: Path): MappingSet {
    return if (format == "tsrg2") {
        val modifiedTSRG2 = path.toFile()
            .readLines(StandardCharsets.UTF_8)
            .stream()
            .filter {
                !it.startsWith("tsrg2") &&
                        !it.startsWith("\t\t")
            }
            .collect(Collectors.joining("\n"))

        val input = ByteArrayInputStream(
            modifiedTSRG2.toByteArray(StandardCharsets.UTF_8)
        )

        MappingFormats.byId("tsrg")
            .createReader(input)
            .read()
    } else {
        MappingFormats.byId(format).read(path)
    }
}

private val Project.intermediaryMappings: Mappings
    get() {
        project.tasks.findByName("genSrgs")?.let {
            return Mappings(
                "searge",
                it.property("mcpToSrg") as File,
                "srg",
                listOf(it)
            )
        }

        project.tasks.findByName("createMcpToSrg")?.let {
            val output = it.property("output")

            return if (output is File) {
                Mappings("searge", output, "tsrg", listOf(it))
            } else {
                Mappings(
                    "searge",
                    (output as RegularFileProperty).get().asFile,
                    "tsrg2",
                    listOf(it)
                )
            }
        }

        tinyMappingsWithSrg?.let {
            return Mappings("searge", it, "tiny", emptyList())
        }

        return Mappings("yarn", tinyMappings, "tiny", emptyList())
    }

data class Mappings(
    val type: String,
    val file: File,
    val format: String,
    val tasks: List<Task>
)

private val Project.notchMappings: Mappings?
    get() {
        project.tasks.findByName("genSrgs")?.let {
            return null
        }

        project.tasks.findByName("extractSrg")?.let {
            val output = it.property("output")

            return if (output is File) {
                Mappings("notch", output, "tsrg", listOf(it))
            } else {
                Mappings(
                    "notch",
                    (output as RegularFileProperty).get().asFile,
                    "tsrg2",
                    listOf(it)
                )
            }
        }

        return Mappings("notch", tinyMappings, "tiny", emptyList())
    }

private val Project.mappingsProvider: Any
    get() {
        val extension = extensions.findByName("loom")
            ?: extensions.findByName("minecraft")
            ?: throw UnsupportedLoom("Expected `loom` or `minecraft` extension")

        if (!extension.javaClass.name.contains("LoomGradleExtension")) {
            throw UnsupportedLoom(
                "Unexpected extension class name: ${extension.javaClass.name}"
            )
        }

        listOf(
            "mappingConfiguration",
            "mappingsProvider"
        ).forEach { property ->
            extension.maybeGetGroovyProperty(property)?.also {
                return it
            }
        }

        throw UnsupportedLoom("Failed to find mappings provider")
    }

private val Project.tinyMappings: File
    get() {
        val provider = mappingsProvider

        provider.maybeGetGroovyProperty("MAPPINGS_TINY")?.let {
            return it as File
        }

        provider.maybeGetGroovyProperty("tinyMappings")?.let {
            when (it) {
                is File -> return it
                is Path -> return it.toFile()
            }
        }

        throw UnsupportedLoom("Failed to find tiny mappings file")
    }

private val Project.tinyMappingsWithSrg: File?
    get() {
        mappingsProvider.maybeGetGroovyProperty("tinyMappingsWithSrg")?.let {
            val file = (it as Path).toFile()
            if (file.exists()) return file
        }

        return null
    }

private val Task.classpath: FileCollection?
    get() = if (this is AbstractCompile) {
        classpath
    } else {
        try {
            val method = javaClass.getMethod("getLibraries")
            method.invoke(this) as FileCollection?
        } catch (ex: Exception) {
            throw RuntimeException(ex)
        }
    }

private class UnsupportedLoom(msg: String) :
    GradleException("Loom version not supported by preprocess plugin: $msg")

private fun Provider<Directory>.dir(path: String): Provider<Directory> =
    map { it.dir(path) }

private fun String.uppercaseFirstChar(): String =
    replaceFirstChar { it.uppercaseChar() }

private fun Any.maybeGetGroovyProperty(name: String): Any? =
    withGroovyBuilder { metaClass }
        .hasProperty(this, name)
        ?.getProperty(this)