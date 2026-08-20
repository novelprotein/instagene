package org.instagene.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * CPU-bound parallel helpers for the engine module.
 *
 * Uses [Dispatchers.Default] for work stealing across available cores. Callers
 * should ensure that individual tasks are non-trivial (thousands of iterations
 * minimum) to amortize the dispatch overhead. For small collections the
 * sequential path is always faster.
 */
internal object Parallel {

    /** Available processors, floored to 2 so single-core machines still get a pool. */
    val cores: Int = maxOf(2, Runtime.getRuntime().availableProcessors())

    /**
     * Parallel map: applies [transform] to each element of [items] concurrently,
     * up to [Parallel.cores] tasks at a time, preserving encounter order.
     */
    fun <T, R> map(items: List<T>, transform: (T) -> R): List<R> {
        if (items.size <= 1) return items.map(transform)
        return runBlocking(Dispatchers.Default) {
            items.map { item -> async { transform(item) } }.awaitAll()
        }
    }

    /**
     * Parallel map with indexed access: [transform] receives (index, element).
     * Useful when the index is needed but the list is too large to enumerate
     * in a single coroutine.
     */
    fun <T, R> mapIndexed(items: List<T>, transform: (Int, T) -> R): List<R> {
        if (items.size <= 1) return items.mapIndexed(transform)
        return runBlocking(Dispatchers.Default) {
            items.mapIndexed { i, item -> async { transform(i, item) } }.awaitAll()
        }
    }

    /**
     * Parallel filter: retains elements where [predicate] returns true.
     */
    fun <T> filter(items: List<T>, predicate: (T) -> Boolean): List<T> {
        if (items.size <= 1) return items.filter(predicate)
        return runBlocking(Dispatchers.Default) {
            items.map { item -> async { item to predicate(item) } }
                .awaitAll()
                .filter { it.second }
                .map { it.first }
        }
    }

    /**
     * Parallel flatMap: applies [transform] to each element and flattens results.
     */
    fun <T, R> flatMap(items: List<T>, transform: (T) -> List<R>): List<R> {
        if (items.size <= 1) return items.flatMap(transform)
        return runBlocking(Dispatchers.Default) {
            items.map { item -> async { transform(item) } }.awaitAll().flatten()
        }
    }

    /**
     * Java-Stream parallel fallback for simple streaming pipelines.
     * Uses [ForkJoinPool.commonPool] directly — no coroutines overhead.
     */
    fun <T, R> streamMap(items: List<T>, transform: (T) -> R): List<R> {
        if (items.size <= 1) return items.map(transform)
        return items.parallelStream().map { transform(it) }.toList()
    }
}
