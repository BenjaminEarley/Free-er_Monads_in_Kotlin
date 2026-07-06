package ffree.effects

import ffree.Effect
import ffree.Program
import ffree.interpret
import ffree.perform

fun <A> Program<A>.raise(): Program<Result<A>> =
    interpret<Error<*>, A, Result<A>>(
        transformDone = { value -> Program.Done(Result.success(value)) },
        rule = { op, _ ->
            when (op) {
                // Short-circuit: We ignore the 'resume' function
                is Raise -> Program.Done(Result.failure(Exception(op.reason)))
            }
        },
    )

sealed interface Error<out R> : Effect<R>

data class Raise(
    val reason: String,
) : Error<Nothing>

fun fail(reason: String): Program<Nothing> = perform(Raise(reason))
