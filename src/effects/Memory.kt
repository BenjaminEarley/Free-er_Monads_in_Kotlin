package effects

import Effect
import Erased
import Program
import perform
import resume

fun <A> Program<A>.runMemory(initialState: Int): Program<A> {
    // Helper loop to carry the state 's'
    fun loop(
        prog: Program<A>,
        s: Int,
    ): Program<A> =
        when (prog) {
            is Program.Done -> {
                prog
            }

            is Program.Suspended<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val suspended = prog as Program.Suspended<Erased, A>
                val op = suspended.effect
                val q = suspended.pipeline

                if (op is Memory) {
                    when (op) {
                        // State Logic: Resume pipeline with 's', pass 's' forward
                        is Recall -> loop(resume(q, s), s)

                        // State Logic: Resume pipeline with 'Unit', pass 'op.value' forward
                        is Memorize -> loop(resume(q, Unit), op.value)
                    }
                } else {
                    // Relay Logic: Capture flow, execute unknown effect, then recurse
                    val relayQ =
                        Pipeline.Step { y: Erased ->
                            loop(resume(q, y), s)
                        }
                    Program.Suspended(op, relayQ)
                }
            }
        }
    return loop(this, initialState)
}

sealed interface Memory<out R> : Effect<R>

data class Memorize(
    val value: Int,
) : Memory<Unit> // Put

object Recall : Memory<Int> // Get

fun memorize(value: Int) = perform(Memorize(value))

fun recall() = perform(Recall)
