package com.replaymod.gradle.preprocess

import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.api.mappings.layered.MappingContext
import net.fabricmc.loom.api.mappings.layered.MappingLayer
import net.fabricmc.loom.api.mappings.layered.spec.MappingsSpec
import net.fabricmc.mappingio.MappingVisitor
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.kotlin.dsl.getByType

/**
 * Bridge to Loom APIs which are only required when preprocessing crosses an
 * obfuscated <-> unobfuscated Minecraft-version boundary.
 *
 * Keeping these references in a separate class is intentional:
 *
 * - legacy mapped -> mapped projects keep using the existing reflection path;
 * - modern Loom classes are not touched unless a graph edge actually needs
 *   Mojang/identity mappings;
 * - the main PreprocessPlugin remains largely independent from Loom's public
 *   API surface.
 */
internal object ModernLoomMappingBridge {

    private const val MOJANG_MAPPINGS_CONFIGURATION =
        "preprocessMojangMappings"

    /**
     * Prepares Mojang mappings for one mapped Minecraft version.
     *
     * This must be called while the project is still being configured, before
     * Loom finalizes/evaluates its layered mappings.
     *
     * The extra no-op layer deliberately incorporates the Minecraft version
     * into the layered mapping specification hash. This prevents mapping
     * artifacts for different Minecraft versions from accidentally sharing
     * the same generated coordinates/cache location.
     */
    fun prepareMojangMappings(
        project: Project,
        mcVersion: Int,
    ): Configuration {
        project.configurations
            .findByName(
                MOJANG_MAPPINGS_CONFIGURATION
            )
            ?.let {
                return it
            }

        val configuration =
            project.configurations.create(
                MOJANG_MAPPINGS_CONFIGURATION
            ) {
                isCanBeResolved = true
                isCanBeConsumed = false
                isVisible = false

                description =
                    "Mojang mappings used by the preprocessor " +
                            "for mapped/unobfuscated version transitions."
            }

        val loom =
            project.extensions
                .getByType<LoomGradleExtensionAPI>()

        val dependency =
            loom.layered {
                officialMojangMappings()

                /*
                 * Loom's layered mapping identity historically did not include
                 * the Minecraft version itself. Give this otherwise empty
                 * layer a version-specific hash so mappings belonging to two
                 * different Minecraft versions cannot collide.
                 */
                addLayer(
                    object :
                        MappingsSpec<NoOpMappingLayer> {

                        override fun createLayer(
                            ctx: MappingContext,
                        ): NoOpMappingLayer {
                            return NoOpMappingLayer()
                        }

                        override fun hashCode():
                                Int {
                            return mcVersion
                        }
                    }
                )
            }

        project.dependencies.add(
            configuration.name,
            dependency,
        )

        return configuration
    }

    /**
     * Returns Mojang mappings previously prepared for [project].
     *
     * We intentionally do not create them lazily here because Loom layered
     * mappings need to be declared during project configuration.
     */
    fun requireMojangMappings(
        project: Project,
    ): Configuration {
        return requireNotNull(
            project.configurations.findByName(
                MOJANG_MAPPINGS_CONFIGURATION
            )
        ) {
            "Mojang mappings were not prepared for project " +
                    "`${project.path}` before preprocessing."
        }
    }

    /**
     * Returns the already-named Minecraft jars exposed by modern Loom.
     *
     * For an unobfuscated Minecraft version these jars are used to generate
     * identity mappings:
     *
     *     official == named == mojang
     */
    fun namedMinecraftJars(
        project: Project,
    ): FileCollection {
        return project.extensions
            .getByType<LoomGradleExtensionAPI>()
            .namedMinecraftJars
    }

    /**
     * Empty layered-mapping component used only to give a mapping dependency
     * a Minecraft-version-specific identity.
     */
    private class NoOpMappingLayer :
        MappingLayer {

        override fun visit(
            visitor: MappingVisitor,
        ) {
            // Intentionally empty.
        }
    }
}