package gg.grounds.scene.minestom.internal

internal const val NANOS_PER_MILLI: Long = 1_000_000L

internal fun millisToNanosSaturated(millis: Long): Long =
    if (millis > Long.MAX_VALUE / NANOS_PER_MILLI) Long.MAX_VALUE else millis * NANOS_PER_MILLI

internal fun elapsedNanos(now: Long, since: Long): Long {
    val elapsed = now - since
    return if (elapsed >= 0L) elapsed else Long.MAX_VALUE
}

internal fun elapsedLessThan(now: Long, since: Long, duration: Long): Boolean =
    java.lang.Long.compareUnsigned(now - since, duration) < 0

internal fun elapsedAtLeast(now: Long, since: Long, duration: Long): Boolean =
    !elapsedLessThan(now, since, duration)
