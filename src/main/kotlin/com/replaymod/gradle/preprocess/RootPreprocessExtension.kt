package com.replaymod.gradle.preprocess

import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.property
import java.io.File
import javax.inject.Inject

/**
 * Root configuration and version graph for the preprocessor.
 *
 * The root extension owns the canonical list of configured Minecraft
 * version nodes.
 *
 * A node with non-null mappings represents an obfuscated/mapped version.
 * A node with null mappings represents an unobfuscated version.
 */
open class RootPreprocessExtension @Inject constructor(
    objects: ObjectFactory,
) : ProjectGraphNodeDSL {

    /**
     * Whether extra mappings must resolve strictly.
     *
     * Defaults to false for backwards compatibility.
     */
    val strictExtraMappings =
        objects.property<Boolean>()

    /**
     * Lazily generated rooted project graph.
     */
    private var rootNode:
            ProjectGraphNode? = null

    /**
     * Canonical version-node collection.
     *
     * LinkedHashSet semantics are intentional:
     *
     * - nodes remain unique;
     * - declaration order remains deterministic.
     */
    private val nodes =
        linkedSetOf<Node>()

    /**
     * Returns the rooted graph for [mainProject].
     */
    fun getRootNode(
        mainProject: String,
    ): ProjectGraphNode? {
        return rootNode
            ?: linkNodes(mainProject)
                ?.also {
                    rootNode = it
                }
    }

    /**
     * Returns a read-only snapshot of all configured nodes.
     *
     * Kept as a stable Gradle-facing API because consumers such as DDS
     * need Minecraft version metadata before individual child projects
     * apply the preprocessor plugin.
     */
    fun getNodes():
            List<Node> {
        return nodes.toList()
    }

    /**
     * Finds a configured version node by Gradle project name.
     */
    fun findNode(
        project: String,
    ): Node? {
        return nodes.firstOrNull {
            it.project == project
        }
    }

    /**
     * Returns a configured node or throws a useful configuration error.
     */
    fun requireNode(
        project: String,
    ): Node {
        return requireNotNull(
            findNode(project)
        ) {
            buildString {
                append(
                    "Preprocess graph does not contain project `"
                )
                append(project)
                append("`.")

                if (nodes.isNotEmpty()) {
                    append(
                        " Configured projects: "
                    )

                    append(
                        nodes.joinToString(
                            ", "
                        ) {
                            it.project
                        }
                    )
                }
            }
        }
    }

    /**
     * Returns whether [project] exists in the preprocess graph.
     */
    fun hasNode(
        project: String,
    ): Boolean {
        return findNode(project) != null
    }

    /**
     * Returns the configured Minecraft version for [project].
     */
    fun getMcVersion(
        project: String,
    ): Int? {
        return findNode(project)
            ?.mcVersion
    }

    /**
     * Creates and registers a version node.
     *
     * mappings:
     *
     * non-null
     *     Version uses a mapped/obfuscated namespace.
     *
     * null
     *     Version is unobfuscated.
     */
    fun createNode(
        project: String,
        mcVersion: Int,
        mappings: String?,
    ): Node {
        require(
            project.isNotBlank()
        ) {
            "Preprocess project name must not be blank."
        }

        require(
            findNode(project) == null
        ) {
            "Duplicate preprocess project node `$project`."
        }

        return Node(
            project = project,
            mcVersion = mcVersion,
            mappings = mappings,
        ).also {
            nodes.add(it)
        }
    }

    /**
     * Converts the bidirectional declaration graph into the rooted graph
     * consumed by preprocessing tasks.
     */
    private fun linkNodes(
        mainProject: String,
    ): ProjectGraphNode? {
        if (nodes.isEmpty()) {
            return null
        }

        val first =
            requireNotNull(
                findNode(mainProject)
            ) {
                buildString {
                    append(
                        "Configured main project `"
                    )
                    append(mainProject)
                    append(
                        "` is not present in the preprocess graph."
                    )

                    if (nodes.isNotEmpty()) {
                        append(
                            " Configured projects: "
                        )

                        append(
                            nodes.joinToString(
                                ", "
                            ) {
                                it.project
                            }
                        )
                    }
                }
            }

        /*
         * Preserve the existing graph traversal semantics.
         *
         * Node links are bidirectional while ProjectGraphNode is the rooted
         * representation used for inheritance and mapping direction.
         */
        val visited =
            mutableSetOf<Node>()

        fun Node.breadthFirstSearch():
                ProjectGraphNode {

            val graphNode =
                ProjectGraphNode(
                    project = project,
                    mcVersion = mcVersion,
                    mappings = mappings,
                )

            links.forEach {
                    (
                        otherNode,
                        extraMappings,
                    ),
                ->

                if (
                    visited.add(
                        otherNode
                    )
                ) {
                    graphNode.links.add(
                        Pair(
                            otherNode
                                .breadthFirstSearch(),
                            extraMappings,
                        )
                    )
                }
            }

            return graphNode
        }

        return first
            .breadthFirstSearch()
    }

    override fun addNode(
        project: String,
        mcVersion: Int,
        mappings: String?,
        extraMappings: File?,
        invertMappings: Boolean,
    ): ProjectGraphNode {

        check(
            rootNode == null
        ) {
            "Only one root node may be set."
        }

        check(
            extraMappings == null
        ) {
            "Cannot add extra mappings to root node."
        }

        return ProjectGraphNode(
            project = project,
            mcVersion = mcVersion,
            mappings = mappings,
        ).also {
            rootNode = it
        }
    }

    /**
     * Optional root-project-relative mainProject location.
     */
    val mainProjectFile =
        objects.property<String>()

    /**
     * Optional child-project-relative mainProject location.
     */
    val mainProjectFileRel =
        objects.property<String>()
}

/**
 * Mutable node used while declaring the version graph.
 */
class Node(
    val project: String,
    val mcVersion: Int,
    val mappings: String?,
) {

    /**
     * Whether this Minecraft version requires named mappings.
     *
     * null mappings explicitly represent an unobfuscated version.
     */
    val isObfuscated:
            Boolean
        get() =
            mappings != null

    /**
     * Adjacent version nodes.
     *
     * Value:
     *
     *     Pair(
     *         extra mapping file,
     *         mapping inversion flag
     *     )
     */
    internal val links =
        linkedMapOf<
                Node,
                Pair<File?, Boolean>,
                >()

    /**
     * Links two adjacent version nodes.
     */
    fun link(
        other: Node,
        extraMappings: File? = null,
    ) {
        require(
            other !== this
        ) {
            "A preprocess node cannot link to itself: `$project`."
        }

        this.links[other] =
            Pair(
                extraMappings,
                false,
            )

        other.links[this] =
            Pair(
                extraMappings,
                true,
            )
    }

    override fun toString():
            String {
        return "Node(" +
                "project='$project', " +
                "mcVersion=$mcVersion, " +
                "mappings=${mappings?.let { "'$it'" } ?: "null"}, " +
                "isObfuscated=$isObfuscated" +
                ")"
    }
}

/**
 * DSL shared by root and recursively declared graph nodes.
 */
interface ProjectGraphNodeDSL {

    operator fun String.invoke(
        mcVersion: Int,
        mappings: String?,
        extraMappings: File? = null,
        configure:
        ProjectGraphNodeDSL.() -> Unit = {},
    ) {
        addNode(
            project = this,
            mcVersion = mcVersion,
            mappings = mappings,
            extraMappings = extraMappings,
        ).configure()
    }

    fun addNode(
        project: String,
        mcVersion: Int,
        mappings: String?,
        extraMappings: File? = null,
        invertMappings: Boolean = false,
    ): ProjectGraphNodeDSL
}

/**
 * Rooted representation consumed by PreprocessPlugin.
 */
open class ProjectGraphNode(
    val project: String,
    val mcVersion: Int,
    val mappings: String?,
    val links:
    MutableList<
            Pair<
                    ProjectGraphNode,
                    Pair<File?, Boolean>,
                    >
            > = mutableListOf(),
) : ProjectGraphNodeDSL {

    /**
     * Whether this node uses mapped/obfuscated names.
     */
    val isObfuscated:
            Boolean
        get() =
            mappings != null

    override fun addNode(
        project: String,
        mcVersion: Int,
        mappings: String?,
        extraMappings: File?,
        invertMappings: Boolean,
    ): ProjectGraphNodeDSL {

        return ProjectGraphNode(
            project = project,
            mcVersion = mcVersion,
            mappings = mappings,
        ).also {
            links.add(
                Pair(
                    it,
                    Pair(
                        extraMappings,
                        invertMappings,
                    ),
                )
            )
        }
    }

    /**
     * Finds a project recursively from this rooted graph.
     */
    fun findNode(
        project: String,
    ): ProjectGraphNode? {

        if (
            project == this.project
        ) {
            return this
        }

        for (
        (
            child,
            _,
        ) in links
        ) {
            val result =
                child.findNode(
                    project
                )

            if (
                result != null
            ) {
                return result
            }
        }

        return null
    }

    /**
     * Finds the parent edge for [node].
     */
    fun findParent(
        node: ProjectGraphNode,
    ): Pair<
            ProjectGraphNode,
            Pair<File?, Boolean>,
            >? {

        if (
            node == this
        ) {
            return null
        }

        for (
        (
            child,
            extraMappings,
        ) in links
        ) {
            if (
                child == node
            ) {
                return Pair(
                    this,
                    extraMappings,
                )
            }

            val nested =
                child.findParent(
                    node
                )

            if (
                nested != null
            ) {
                return nested
            }
        }

        return null
    }

    override fun toString():
            String {
        return "ProjectGraphNode(" +
                "project='$project', " +
                "mcVersion=$mcVersion, " +
                "mappings=${mappings?.let { "'$it'" } ?: "null"}, " +
                "isObfuscated=$isObfuscated" +
                ")"
    }
}