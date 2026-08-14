package com.replaymod.gradle.preprocess

import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.property
import java.io.File
import javax.inject.Inject

/**
 * Root configuration and version graph for the preprocessor.
 *
 * The root extension owns the canonical list of configured version nodes.
 *
 * A version node contains:
 *
 * - Gradle project name;
 * - Minecraft version number;
 * - mapping namespace identifier;
 * - links to adjacent Minecraft versions.
 *
 * The mutable graph is kept private. Consumers should use the query methods
 * exposed by this extension instead of depending on the backing collection.
 */
open class RootPreprocessExtension @Inject constructor(
    objects: ObjectFactory,
) : ProjectGraphNodeDSL {

    /**
     * Whether extra mappings must resolve strictly.
     *
     * Defaults to false for backwards compatibility.
     */
    val strictExtraMappings = objects.property<Boolean>()

    /**
     * Lazily generated rooted project graph.
     *
     * Once generated, the graph must no longer be structurally modified.
     */
    private var rootNode: ProjectGraphNode? = null

    /**
     * Version nodes in declaration order.
     *
     * linkedSetOf is intentional:
     *
     * - uniqueness is retained;
     * - iteration order remains deterministic;
     * - existing Groovy builds relying on declaration order remain stable.
     */
    private val nodes = linkedSetOf<Node>()

    /**
     * Returns the rooted project graph whose root is [mainProject].
     *
     * The graph is generated lazily and cached after the first request.
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
     * Returns a read-only snapshot of all configured version nodes.
     *
     * This method is part of the public Gradle-facing API.
     *
     * In particular, multi-version projects may use it during root project
     * configuration to expose Minecraft version metadata to child projects
     * before the child preprocessor plugin itself is applied.
     */
    fun getNodes(): List<Node> {
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
     * Returns a configured node or fails with a useful configuration error.
     */
    fun requireNode(
        project: String,
    ): Node {
        return requireNotNull(
            findNode(project)
        ) {
            buildString {
                append("Preprocess graph does not contain project `")
                append(project)
                append("`.")

                if (nodes.isNotEmpty()) {
                    append(" Configured projects: ")
                    append(
                        nodes.joinToString(", ") {
                            it.project
                        }
                    )
                }
            }
        }
    }

    /**
     * Returns whether a version node exists for [project].
     */
    fun hasNode(
        project: String,
    ): Boolean {
        return findNode(project) != null
    }

    /**
     * Returns the configured Minecraft version for [project], or null when
     * the project is not part of the preprocess graph.
     */
    fun getMcVersion(
        project: String,
    ): Int? {
        return findNode(project)
            ?.mcVersion
    }

    /**
     * Creates and registers one version node.
     *
     * Project names are unique identifiers in the Gradle multi-project build,
     * therefore registering the same project twice is almost certainly a
     * configuration error and is rejected immediately.
     */
    fun createNode(
        project: String,
        mcVersion: Int,
        mappings: String,
    ): Node {
        require(project.isNotBlank()) {
            "Preprocess project name must not be blank."
        }

        require(findNode(project) == null) {
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
     * Converts the undirected version-node graph into the rooted graph used
     * internally by preprocessing tasks.
     *
     * The selected main project must be explicitly present in the configured
     * graph. Falling back silently to the first declared node can cause the
     * raw source tree to be interpreted as the wrong Minecraft version.
     */
    private fun linkNodes(
        mainProject: String,
    ): ProjectGraphNode? {
        if (nodes.isEmpty()) {
            return null
        }

        val first = requireNotNull(
            findNode(mainProject)
        ) {
            buildString {
                append("Configured main project `")
                append(mainProject)
                append("` is not present in the preprocess graph.")

                if (nodes.isNotEmpty()) {
                    append(" Configured projects: ")
                    append(
                        nodes.joinToString(", ") {
                            it.project
                        }
                    )
                }
            }
        }

        /*
         * Do not change the traversal semantics here casually.
         *
         * The graph is declared using bidirectional Node links while
         * ProjectGraphNode is a rooted representation used by the existing
         * source-inheritance and reverse-mapping logic.
         *
         * Compatibility with existing preprocessor projects therefore takes
         * precedence over making this traversal look like a conventional tree
         * conversion.
         */
        val visited =
            mutableSetOf<Node>()

        fun Node.breadthFirstSearch(): ProjectGraphNode {
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

        return first.breadthFirstSearch()
    }

    override fun addNode(
        project: String,
        mcVersion: Int,
        mappings: String,
        extraMappings: File?,
        invertMappings: Boolean,
    ): ProjectGraphNode {
        check(rootNode == null) {
            "Only one root node may be set."
        }

        check(extraMappings == null) {
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
     * Optional absolute/root-project-relative location of mainProject.
     *
     * Retained for compatibility with the Fallen-Breath extension.
     */
    val mainProjectFile =
        objects.property<String>()

    /**
     * Optional child-project-relative location of mainProject.
     *
     * Retained for compatibility with the Fallen-Breath extension.
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
    val mappings: String,
) {

    /**
     * Adjacent version nodes.
     *
     * Pair:
     *
     *     extra mapping file
     *     mapping inversion flag
     */
    internal val links =
        linkedMapOf<
                Node,
                Pair<File?, Boolean>,
                >()

    /**
     * Links two adjacent version nodes.
     *
     * Links are deliberately bidirectional because the selected main project
     * may sit anywhere inside the supported Minecraft-version graph.
     */
    fun link(
        other: Node,
        extraMappings: File? = null,
    ) {
        require(other !== this) {
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

    override fun toString(): String {
        return "Node(" +
                "project='$project', " +
                "mcVersion=$mcVersion, " +
                "mappings='$mappings'" +
                ")"
    }
}

/**
 * DSL shared by the root graph and recursively declared project graph nodes.
 */
interface ProjectGraphNodeDSL {

    operator fun String.invoke(
        mcVersion: Int,
        mappings: String,
        extraMappings: File? = null,
        configure: ProjectGraphNodeDSL.() -> Unit = {},
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
        mappings: String,
        extraMappings: File? = null,
        invertMappings: Boolean = false,
    ): ProjectGraphNodeDSL
}

/**
 * Rooted representation of the configured version graph.
 *
 * This is the representation consumed by PreprocessPlugin when determining
 * source inheritance and mapping direction.
 */
open class ProjectGraphNode(
    val project: String,
    val mcVersion: Int,
    val mappings: String,
    val links:
    MutableList<
            Pair<
                    ProjectGraphNode,
                    Pair<File?, Boolean>,
                    >
            > = mutableListOf(),
) : ProjectGraphNodeDSL {

    override fun addNode(
        project: String,
        mcVersion: Int,
        mappings: String,
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
     * Finds a project node recursively from this rooted graph.
     */
    fun findNode(
        project: String,
    ): ProjectGraphNode? {
        if (project == this.project) {
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

            if (result != null) {
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
        if (node == this) {
            return null
        }

        for (
        (
            child,
            extraMappings,
        ) in links
        ) {
            if (child == node) {
                return Pair(
                    this,
                    extraMappings,
                )
            }

            val nested =
                child.findParent(
                    node
                )

            if (nested != null) {
                return nested
            }
        }

        return null
    }

    override fun toString(): String {
        return "ProjectGraphNode(" +
                "project='$project', " +
                "mcVersion=$mcVersion, " +
                "mappings='$mappings'" +
                ")"
    }
}