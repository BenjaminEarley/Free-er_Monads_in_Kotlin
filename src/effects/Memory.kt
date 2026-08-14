package ffree.effects

import ffree.Effect
import ffree.Program
import ffree.handleS
import ffree.interpretS
import ffree.perform
import ffree.program

fun <A> Program<A>.memory(initialState: Int): Program<A> =
    handleS<Memory<*>, Int, A>(initialState) { s, op ->
        when (op) {
            is Recall -> s to s // state unchanged, return the state
            is Memorize -> op.value to Unit // new state, return Unit
        }
    }

/** Like [memory], but also returns the final state alongside the result. */
fun <A> Program<A>.memoryWithState(initialState: Int): Program<Pair<A, Int>> =
    interpretS<Memory<*>, Int, A, Pair<A, Int>>(
        initialState = initialState,
        transformDone = { s, a -> Program.Done(a to s) },
        rule = { s, op, resume ->
            when (op) {
                is Recall -> resume(s, s)
                is Memorize -> resume(op.value, Unit)
            }
        },
    )

/**
 * Run this program as a transaction: its Memory effects hit a local copy of the
 * state, seeded by reading the downstream handler, and the final local state is
 * committed downstream only if the program completes. If it fails part-way
 * (e.g. a [ffree.effects.Raise] handled outside), the commit is part of the
 * abandoned continuation and never runs — the downstream state stays untouched.
 */
fun <A> Program<A>.transactional(): Program<A> =
    program {
        val snapshot = recall().bind()
        val (result, finalState) = this@transactional.memoryWithState(snapshot).bind()
        memorize(finalState).bind()
        result
    }

sealed interface Memory<out R> : Effect<R>

data class Memorize(
    val value: Int,
) : Memory<Unit> // Put

data object Recall : Memory<Int> // Get

fun memorize(value: Int) = perform(Memorize(value))

fun recall() = perform(Recall)
