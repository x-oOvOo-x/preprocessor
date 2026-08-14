package com.replaymod.gradle.preprocess

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.MappingVisitor
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.lorenz.model.*
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.extension

/**
 * Reads either a plain mapping file or a Loom mapping jar.
 *
 * Loom layered mappings may be packaged as:
 *
 *     mappings/mappings.tiny
 *
 * inside a jar. Paths inside ZipFS must not be converted with Path.toFile().
 */
internal fun readMappings(path: Path, visitor: MappingVisitor) {
    if (path.extension.equals("jar", ignoreCase = true)) {
        FileSystems.newFileSystem(path).use { fileSystem ->
            val mappingsPath = fileSystem.getPath("mappings", "mappings.tiny")

            require(Files.exists(mappingsPath)) {
                "Mapping jar `$path` does not contain mappings/mappings.tiny"
            }

            mappingsPath.bufferedReader().use { reader ->
                MappingReader.read(reader, visitor)
            }
        }
    } else {
        MappingReader.read(path, visitor)
    }
}

fun File.readMappings(): MappingSet {
    val ext = name.substring(name.lastIndexOf(".") + 1)
    val format = MappingFormats.REGISTRY.values().find {
        it.standardFileExtension.orElse(null) == ext
    } ?: throw UnsupportedOperationException("Cannot find mapping format for $this")

    return format.read(toPath())
}

// SRG doesn't track class names, so manual class mappings need to be split
// from mappings which are applied after transitioning to SRG names.
fun MappingSet.splitOffClassMappings(): MappingSet {
    val clsMap = MappingSet.create()

    for (cls in topLevelClassMappings) {
        val clsOnly = clsMap.getOrCreateTopLevelClassMapping(cls.obfuscatedName)
        clsOnly.deobfuscatedName = cls.deobfuscatedName
        cls.deobfuscatedName = cls.obfuscatedName

        for (inner in cls.innerClassMappings) {
            inner.splitOffInnerClassMappings(
                clsOnly.getOrCreateInnerClassMapping(inner.obfuscatedName)
            )
        }
    }

    return clsMap
}

fun InnerClassMapping.splitOffInnerClassMappings(to: InnerClassMapping) {
    to.deobfuscatedName = deobfuscatedName
    deobfuscatedName = obfuscatedName

    for (inner in innerClassMappings) {
        inner.splitOffInnerClassMappings(
            to.getOrCreateInnerClassMapping(inner.obfuscatedName)
        )
    }
}

// Like a.merge(b), except mappings which exist only in b are preserved.
fun MappingSet.mergeBoth(
    b: MappingSet,
    into: MappingSet = MappingSet.create()
): MappingSet {
    topLevelClassMappings.forEach { aClass ->
        val bClass = b.getTopLevelClassMapping(aClass.deobfuscatedName).orElse(null)

        if (bClass != null) {
            val merged = into.getOrCreateTopLevelClassMapping(aClass.obfuscatedName)
            merged.deobfuscatedName = bClass.deobfuscatedName
            aClass.mergeBoth(bClass, merged)
        } else {
            aClass.copy(into)
        }
    }

    b.topLevelClassMappings.forEach {
        if (!topLevelClassMappings.any { c -> c.deobfuscatedName == it.obfuscatedName }) {
            it.copy(into)
        }
    }

    return into
}

fun <T : ClassMapping<T, *>> ClassMapping<T, *>.mergeBoth(
    b: ClassMapping<T, *>,
    merged: ClassMapping<T, *>
) {
    fieldMappings.forEach {
        val bField = b.getFieldMapping(it.deobfuscatedName).orElse(null)

        if (bField != null) {
            it.join(bField, merged)
        } else {
            it.copy(merged)
        }
    }

    b.fieldMappings.forEach {
        if (!fieldMappings.any { c -> c.deobfuscatedSignature == it.signature }) {
            it.copy(merged)
        }
    }

    methodMappings.forEach {
        val bMethod = b.getMethodMapping(it.deobfuscatedSignature).orElse(null)

        if (bMethod != null) {
            it.join(bMethod, merged)
        } else {
            it.copy(merged)
        }
    }

    b.methodMappings.forEach {
        if (!methodMappings.any { c -> c.deobfuscatedSignature == it.signature }) {
            it.copy(merged)
        }
    }

    innerClassMappings.forEach { aClass ->
        val bClass = b.getInnerClassMapping(aClass.deobfuscatedName).orElse(null)

        if (bClass != null) {
            val mergedInner = merged.getOrCreateInnerClassMapping(aClass.obfuscatedName)
            mergedInner.deobfuscatedName = bClass.deobfuscatedName
            aClass.mergeBoth(bClass, mergedInner)
        } else {
            aClass.copy(merged)
        }
    }

    b.innerClassMappings.forEach {
        if (!innerClassMappings.any { c -> c.deobfuscatedName == it.obfuscatedName }) {
            it.copy(merged)
        }
    }
}

// Like MappingSet.join, but entries which do not exist in b are excluded.
// Field/method joining ignores changed descriptors where possible.
fun MappingSet.join(
    b: MappingSet,
    into: MappingSet = MappingSet.create()
): MappingSet {
    topLevelClassMappings.forEach { classA ->
        b.getTopLevelClassMapping(classA.deobfuscatedName).ifPresent { classB ->
            classA.join(classB, into)
        }
    }

    return into
}

fun TopLevelClassMapping.join(b: TopLevelClassMapping, into: MappingSet) {
    val merged = into.getOrCreateTopLevelClassMapping(obfuscatedName)
    merged.deobfuscatedName = b.deobfuscatedName

    fieldMappings.forEach { fieldA ->
        val fieldB = b.getFieldMapping(fieldA.deobfuscatedSignature).orElse(null)
            ?: b.getFieldMapping(fieldA.deobfuscatedName).orElse(null)
            ?: return@forEach

        fieldA.join(fieldB, merged)
    }

    methodMappings.forEach { methodA ->
        val methodB = b.getMethodMapping(methodA.deobfuscatedSignature).orElse(null)
            ?: b.methodMappings.find { methodA.deobfuscatedName == it.obfuscatedName }
            ?: return@forEach

        methodA.join(methodB, merged)
    }

    innerClassMappings.forEach { classA ->
        b.getInnerClassMapping(classA.deobfuscatedName).ifPresent { classB ->
            classA.join(classB, merged)
        }
    }
}

fun InnerClassMapping.join(b: InnerClassMapping, into: ClassMapping<*, *>) {
    val merged = into.getOrCreateInnerClassMapping(obfuscatedName)
    merged.deobfuscatedName = b.deobfuscatedName

    fieldMappings.forEach { fieldA ->
        val fieldB = b.getFieldMapping(fieldA.deobfuscatedSignature).orElse(null)
            ?: b.getFieldMapping(fieldA.deobfuscatedName).orElse(null)
            ?: return@forEach

        fieldA.join(fieldB, merged)
    }

    methodMappings.forEach { methodA ->
        val methodB = b.getMethodMapping(methodA.deobfuscatedSignature).orElse(null)
            ?: b.methodMappings.find { methodA.deobfuscatedName == it.obfuscatedName }
            ?: return@forEach

        methodA.join(methodB, merged)
    }

    innerClassMappings.forEach { classA ->
        b.getInnerClassMapping(classA.deobfuscatedName).ifPresent { classB ->
            classA.join(classB, merged)
        }
    }
}

fun FieldMapping.join(
    with: FieldMapping,
    parent: ClassMapping<*, *>
): FieldMapping =
    parent.createFieldMapping(signature)
        .setDeobfuscatedName(with.deobfuscatedName)

fun MethodMapping.join(
    with: MethodMapping,
    parent: ClassMapping<*, *>
): MethodMapping =
    parent.createMethodMapping(signature)
        .setDeobfuscatedName(with.deobfuscatedName)
        .also { merged ->
            parameterMappings.forEach { paramA ->
                val paramB = with.getOrCreateParameterMapping(paramA.index)
                paramA.merge(paramB, merged)
            }
        }