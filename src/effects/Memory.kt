package ffree.effects

import ffree.Effect
import ffree.Program
import ffree.handleS
import ffree.perform

fun <A> Program<A>.memory(initialState: Int): Program<A> =
    handleS<Memory<*>, Int, A>(initialState) { s, op ->
        when (op) {
            is Recall -> s to s // state unchanged, return the state
            is Memorize -> op.value to Unit // new state, return Unit
        }
    }

sealed interface Memory<out R> : Effect<R>

data class Memorize(
    val value: Int,
) : Memory<Unit> // Put

data object Recall : Memory<Int> // Get

fun memorize(value: Int) = perform(Memorize(value))

fun recall() = perform(Recall)
