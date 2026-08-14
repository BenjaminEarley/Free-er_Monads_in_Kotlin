/*
 * Nondeterminism: multi-shot continuations.
 *
 * choose(options) suspends on a Choose effect; the collectAll handler resumes
 * the SAME captured continuation once per option and concatenates the results.
 * Resuming a continuation more than once is impossible with one-shot suspend
 * coroutines — it works here because the continuation is an immutable Pipeline
 * value that can be replayed.
 *
 * Programs under collectAll must be built with perform/flatMap chains:
 * program { } blocks are single-shot per interpretation and throw on a second
 * resumption of the same suspension.
 *
 * Three consequences worth knowing: d sequential choices of s options explore
 * s^d branches — there is no implicit pruning. A stateful handler applied inside
 * collectAll backtracks its state per branch; applied outside, one global state
 * threads across all branches. And catchError inside collectAll recovers a failed
 * branch in isolation; outside, one failed branch replaces the entire collection.
 */
@file:Suppress("UNCHECKED_CAST")

package ffree.examples

import ffree.Effect
import ffree.Program
import ffree.flatMap
import ffree.interpret
import ffree.map
import ffree.perform
import ffree.program
import ffree.runOrThrow

sealed interface NonDet<out R> : Effect<R>

data class Choose(
    val options: List<Any?>,
) : NonDet<Any?>

fun <T> choose(options: List<T>): Program<T> = perform(Choose(options)) as Program<T>

/** Prune this branch unless [condition] holds. */
fun guard(condition: Boolean): Program<Unit> = if (condition) Program.DONE_UNIT else choose(emptyList())

/** Run every branch to completion and collect all results in order. */
fun <A> Program<A>.collectAll(): Program<List<A>> =
    interpret<NonDet<*>, A, List<A>>(
        transformDone = { a -> Program.Done(listOf(a)) },
        rule = { op, resume ->
            when (op) {
                is Choose ->
                    // The program{} block defers the resume calls out of the rule body
                    // (a direct resume is single-shot); by the time the block runs, each
                    // resume(option) replays the same captured continuation — multi-shot.
                    // The block itself is interpreted once per Choose occurrence, so its
                    // own single-shot constraint is not violated.
                    program {
                        val all = mutableListOf<A>()
                        for (option in op.options) {
                            all += resume(option).bind()
                        }
                        all
                    }
            }
        },
    )

/** Binary choice between two programs. */
fun <A> orElse(
    first: Program<A>,
    second: Program<A>,
): Program<A> = choose(listOf(true, false)).flatMap { pickFirst -> if (pickFirst) first else second }

/**
 * Split the search: null if it has no answers, otherwise the first answer paired
 * with the rest of the search as a replayable program. Options past the first
 * answer are stitched into the rest lazily — they are only resumed if it is run.
 */
fun <A> Program<A>.msplit(): Program<Pair<A, Program<A>>?> =
    interpret<NonDet<*>, A, Pair<A, Program<A>>?>(
        transformDone = { a -> Program.Done(a to choose(emptyList<A>())) },
        rule = { op, resume ->
            when (op) {
                is Choose ->
                    program {
                        var found: Pair<A, Program<A>>? = null
                        var next = 0
                        while (found == null && next < op.options.size) {
                            found = resume(op.options[next]).bind()
                            next++
                        }
                        val split = found
                        when {
                            split == null -> null
                            next == op.options.size -> split
                            else -> {
                                val rest =
                                    op.options.subList(next, op.options.size).fold(split.second) { acc, option ->
                                        orElse(acc, unsplit(program { resume(option).bind() }))
                                    }
                                split.first to rest
                            }
                        }
                    }
            }
        },
    )

// Turn a split-shaped program back into an ordinary nondeterministic one.
private fun <A> unsplit(split: Program<Pair<A, Program<A>>?>): Program<A> =
    split.flatMap { s ->
        if (s == null) choose(emptyList()) else orElse(Program.Done(s.first), s.second)
    }

/** Committed choice: the first answer only; the rest of the search is never explored. */
fun <A> once(search: Program<A>): Program<A> =
    search.msplit().flatMap { s ->
        if (s == null) choose(emptyList()) else Program.Done(s.first)
    }

/**
 * Soft cut: if [condition] has any answer, run [then] over every one of its
 * answers; run [otherwise] only when it has none.
 */
fun <A, B> ifte(
    condition: Program<A>,
    then: (A) -> Program<B>,
    otherwise: Program<B>,
): Program<B> =
    condition.msplit().flatMap { s ->
        if (s == null) otherwise else orElse(then(s.first), s.second.flatMap(then))
    }

fun nonDetExample() {
    val evens: Program<Int> =
        choose((1..10).toList()).flatMap { i ->
            guard(i % 2 == 0).map { i }
        }
    println("evens in 1..10       = ${evens.collectAll().runOrThrow()}")

    val pairs: Program<Pair<Int, Int>> =
        choose((1..3).toList()).flatMap { x ->
            choose((x..3).toList()).map { y -> x to y }
        }
    println("ordered pairs        = ${pairs.collectAll().runOrThrow()}")
}

fun committedChoiceExample() {
    val triples: Program<Triple<Int, Int, Int>> =
        choose((1..20).toList()).flatMap { x ->
            choose((x..20).toList()).flatMap { y ->
                choose((y..20).toList()).flatMap { z ->
                    guard(x * x + y * y == z * z).map { Triple(x, y, z) }
                }
            }
        }
    println("all triples in 1..20 = ${triples.collectAll().runOrThrow()}")
    println("once (rest pruned)   = ${once(triples).collectAll().runOrThrow()}")

    // Soft cut: the then-branch ranges over every factor found; the else-branch
    // runs only when a number has none.
    fun factors(n: Int): Program<Int> = choose((2 until n).toList()).flatMap { d -> guard(n % d == 0).map { d } }

    val classified =
        choose((2..8).toList()).flatMap { n ->
            ifte(factors(n), { d -> Program.Done("$n=${d}x${n / d}") }, Program.Done("$n prime"))
        }
    println("soft cut             = ${classified.collectAll().runOrThrow()}")
}
