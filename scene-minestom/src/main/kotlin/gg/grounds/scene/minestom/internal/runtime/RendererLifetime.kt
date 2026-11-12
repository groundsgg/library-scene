package gg.grounds.scene.minestom.internal.runtime

import gg.grounds.scene.minestom.SceneRendererLifetime

internal class RendererLifetime(
    private val current: () -> Boolean,
    private val failure: (Throwable) -> Unit,
    private val cleanup: (java.util.concurrent.CompletionStage<Void>) -> Unit,
) : SceneRendererLifetime {
    private val lock = Any()
    private var invalidated = false
    private var nextId = 0L
    private val callbacks = LinkedHashMap<Long, Runnable>()

    override fun isCurrent(): Boolean = synchronized(lock) { !invalidated && current() }

    override fun runIfCurrent(operation: Runnable): Boolean =
        synchronized(lock) {
            if (invalidated || !current()) false
            else {
                operation.run()
                true
            }
        }

    override fun onInvalidated(callback: Runnable): AutoCloseable {
        val id =
            synchronized(lock) {
                if (invalidated || !current()) null
                else (++nextId).also { callbacks[it] = callback }
            }
        if (id == null) callback.run()
        return AutoCloseable {
            synchronized(lock) { callbacks.remove(id) }
            Unit
        }
    }

    override fun reportFailure(failure: Throwable) {
        if (isCurrent()) this.failure(failure)
    }

    override fun trackCleanup(cleanup: java.util.concurrent.CompletionStage<Void>) {
        this.cleanup(cleanup)
    }

    fun invalidate(): Throwable? {
        val pending =
            synchronized(lock) {
                if (invalidated) return null
                invalidated = true
                callbacks.values.toList().also { callbacks.clear() }
            }
        var failure: Throwable? = null
        pending.forEach { callback ->
            try {
                callback.run()
            } catch (error: Throwable) {
                if (failure == null) failure = error
                else if (error !== failure) failure!!.addSuppressed(error)
            }
        }
        return failure
    }
}
