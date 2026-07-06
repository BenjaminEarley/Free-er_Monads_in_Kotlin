/*
 * Performance benchmark for the type-aligned queue (Pipeline).
 *
 * The paper "Freer Monads, More Extensible Effects" (Kiselyov & Ishii) solves
 * the left-associated bind problem in free monads. Naive free monads have O(n²)
 * interpretation for left-associated chains like ((a >>= f) >>= g) >>= h,
 * because each bind must walk to the end of the accumulated chain.
 *
 * The type-aligned queue (Pipeline) gives O(1) append and amortized O(1) dequeue,
 * making both left- and right-associated chains O(n).
 *
 * This benchmark proves it: if time doubles when n doubles, it's O(n).
 * If it quadrupled, it would be O(n²).
 */

import ffree.Effect
import ffree.Program
import ffree.flatMap
import ffree.interpret
import ffree.map
import ffree.perform
import ffree.runOrThrow

sealed interface Tick<out R> : Effect<R>

data object Inc : Tick<Unit>

fun main() {
    // Warm up the JVM
    repeat(3) {
        buildAndRunLeft(100_000)
        buildAndRunRight(100_000)
    }

    println("=== Left-Associated Chains ===")
    println("((Done(0) >>= f) >>= f) >>= f ... (the pathological case for naive free monads)")
    println()
    benchmarkSizes(::buildAndRunLeft)

    println()
    println("=== Right-Associated Chains ===")
    println("Done(0) >>= (f >>= (f >>= (f ...))) (already efficient in any free monad)")
    println()
    benchmarkSizes(::buildAndRunRight)

    println()
    println("Without the type-aligned queue, left-associated chains will hang.")
}

// Single sample per size, so individual ratios are exposed to JIT/GC noise —
// judge linearity by the trend across sizes, not any one row. The largest
// left-associated sizes allocate millions of closures, so GC can dominate
// their timings at the default heap.
fun benchmarkSizes(run: (Int) -> Pair<Int, Double>) {
    val sizes = listOf(100_000, 200_000, 400_000, 800_000, 1_600_000, 3_200_000)
    var prevTime = 0.0

    for (n in sizes) {
        val (result, elapsed) = run(n)
        val ratio = if (prevTime > 0) elapsed / prevTime else Double.NaN
        println(
            "n=%,10d  result=%,10d  time=%8.1f ms  ratio=%.2f".format(
                n,
                result,
                elapsed,
                ratio,
            ),
        )
        prevTime = elapsed
    }
}

// Left-associated: ((Done(0).flatMap(f)).flatMap(f)).flatMap(f)...
// This is the pathological case that naive free monads handle in O(n²).
// With the type-aligned queue, it should be O(n).
// Construction is timed along with interpretation so both benchmarks measure
// the same thing: buildAndRunRight's build is lazy and necessarily happens
// during interpretation, inside its timed region.
fun buildAndRunLeft(n: Int): Pair<Int, Double> {
    val start = System.nanoTime()
    var program: Program<Int> = Program.Done(0)
    for (i in 1..n) {
        program =
            program.flatMap { count ->
                perform(Inc).map { count + 1 }
            }
    }

    val result =
        program
            .interpret<Tick<*>, Int> { op, resume ->
                when (op) {
                    is Inc -> resume(Unit)
                }
            }.runOrThrow()
    val elapsed = (System.nanoTime() - start) / 1_000_000.0
    return result to elapsed
}

// Right-associated: Done(0).flatMap { f.flatMap { f.flatMap { ... } } }
// This is already efficient in any free monad.
// With the type-aligned queue, it should also be O(n).
fun buildAndRunRight(n: Int): Pair<Int, Double> {
    fun build(
        remaining: Int,
        count: Int,
    ): Program<Int> =
        if (remaining == 0) {
            Program.Done(count)
        } else {
            perform(Inc).flatMap { build(remaining - 1, count + 1) }
        }

    val program = build(n, 0)

    val start = System.nanoTime()
    val result =
        program
            .interpret<Tick<*>, Int> { op, resume ->
                when (op) {
                    is Inc -> resume(Unit)
                }
            }.runOrThrow()
    val elapsed = (System.nanoTime() - start) / 1_000_000.0
    return result to elapsed
}
