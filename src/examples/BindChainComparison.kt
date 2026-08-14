/*
 * Benchmark: sum n ints fed one at a time into a left-associated chain of n
 * binds — three encodings of the same stream consumer.
 *
 * NaiveIt stores its continuation as a composed closure. Its bind re-wraps
 * that closure, so each element fed must re-enter every pending wrapper and
 * re-nest the rest on the way out: O(n²) overall.
 *
 * Storing the pending continuations as a concrete sequence instead makes the
 * run O(n). Program does this with the Pipeline queue (O(1) bind append,
 * amortized O(1) dequeue) while remaining a replayable value that handlers
 * can pattern-match. Kotlin suspend gets there too — the compiler's CPS keeps
 * the continuation as a linked chain of Continuation frames, consumed once —
 * but the result is one-shot and opaque.
 */
package ffree.examples

import ffree.Effect
import ffree.Program
import ffree.flatMap
import ffree.interpretS
import ffree.map
import ffree.perform
import ffree.runOrThrow
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine

// Iteratee whose continuation is a plain function.
private sealed class NaiveIt<out A> {
    class Pure<out A>(
        val value: A,
    ) : NaiveIt<A>()

    class Get<out A>(
        val cont: (Int) -> NaiveIt<A>,
    ) : NaiveIt<A>()
}

// The quadratic pattern: bind on Get re-wraps the continuation.
private fun <A, B> NaiveIt<A>.flatMap(k: (A) -> NaiveIt<B>): NaiveIt<B> =
    when (this) {
        is NaiveIt.Pure -> k(value)
        is NaiveIt.Get -> NaiveIt.Get { i -> cont(i).flatMap(k) }
    }

private val naiveGet: NaiveIt<Int> = NaiveIt.Get { i -> NaiveIt.Pure(i) }

private fun naiveAddGet(x: Int): NaiveIt<Int> = naiveGet.flatMap { i -> NaiveIt.Pure(i + x) }

// Feed the stream start, start+1, ... — each cont(next) re-enters every
// wrapper still pending, so stack use grows with the binds remaining.
private tailrec fun <A> naiveFeedAll(
    iteratee: NaiveIt<A>,
    next: Int,
): A =
    when (iteratee) {
        is NaiveIt.Pure -> iteratee.value
        is NaiveIt.Get -> naiveFeedAll(iteratee.cont(next), next + 1)
    }

// n left-associated binds: ((Pure(0) >>= addGet) >>= addGet) >>= ...
private fun naiveSum(n: Int): Int {
    var acc: NaiveIt<Int> = NaiveIt.Pure(0)
    repeat(n) { acc = acc.flatMap(::naiveAddGet) }
    return naiveFeedAll(acc, 1)
}

// The same iteratee on Program.

sealed interface Stream<out R> : Effect<R>

data object Next : Stream<Int>

private fun addGet(x: Int): Program<Int> = perform(Next).map { i -> i + x }

private fun programSum(n: Int): Int {
    var acc: Program<Int> = Program.Done(0)
    repeat(n) { acc = acc.flatMap(::addGet) }
    return acc
        .interpretS<Stream<*>, Int, Int>(1) { next, op, resume ->
            when (op) {
                is Next -> resume(next + 1, next)
            }
        }.runOrThrow()
}

// The same iteratee as raw suspend lambdas.

// The stream request: park until the driver resumes with the next int.
private class Feed {
    var pending: Continuation<Int>? = null

    suspend fun next(): Int =
        suspendCoroutineUninterceptedOrReturn { cont ->
            pending = cont
            COROUTINE_SUSPENDED
        }
}

private fun suspendSum(n: Int): Int {
    val feed = Feed()
    // The same left-composed chain of n add-next-int steps
    var k: suspend (Int) -> Int = { it }
    repeat(n) {
        val prev = k
        k = { x -> prev(x) + feed.next() }
    }

    var done: Int? = null
    val entry: suspend () -> Int = { k(0) }
    entry.startCoroutine(Continuation(EmptyCoroutineContext) { done = it.getOrThrow() })

    // Feed: resume the parked computation with 1, 2, 3, ...
    var next = 1
    while (done == null) {
        val cont = checkNotNull(feed.pending)
        feed.pending = null
        cont.resume(next++)
    }
    return done
}

private inline fun <T> timed(block: () -> T): Pair<T, Double> {
    val start = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - start) / 1_000_000.0
}

fun bindChainComparison() {
    // Warm up the JIT on all paths before measuring
    repeat(5) {
        naiveSum(1_000)
        suspendSum(1_000)
        programSum(1_000)
    }

    println("Summing n ints fed one at a time through n left-associated binds:")
    println("naive   = continuation as composed closures — reified but O(n²)")
    println("suspend = compiler CPS, concrete continuation chain — O(n), one-shot & opaque")
    println("Program = type-aligned queue — O(n), and a replayable, interceptable value")

    for (n in listOf(2_000, 4_000, 8_000)) {
        val (naiveResult, naiveMs) = timed { naiveSum(n) }
        val (suspendResult, suspendMs) = timed { suspendSum(n) }
        val (programResult, programMs) = timed { programSum(n) }
        val expected = n * (n + 1) / 2
        check(naiveResult == expected && suspendResult == expected && programResult == expected) {
            "expected $expected, got naive=$naiveResult suspend=$suspendResult program=$programResult"
        }
        println(
            "n=%,6d  naive: %8.2f ms  suspend: %6.2f ms  Program: %6.2f ms  naive/Program: %.0fx".format(
                n,
                naiveMs,
                suspendMs,
                programMs,
                naiveMs / programMs,
            ),
        )
    }
}
