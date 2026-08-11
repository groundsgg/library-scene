package gg.grounds.scene.format

import java.util.Collections

internal fun <T> immutableListCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(values.toList())

internal fun <T> immutableSetCopy(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(values.toSet())

internal fun <K, V> immutableMapCopy(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(values.toMap())
