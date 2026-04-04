@file:Suppress("UNCHECKED_CAST")

package effects

import Effect
import Program
import kotlinx.coroutines.runBlocking
import perform
import resume

// Handlers that need real async I/O emit IO effects via performIO { }.
// A single suspend runIO() at the edge handles them all.
sealed interface IO<out R> : Effect<R>

data class SuspendIO<R>(
    val thunk: suspend () -> R,
) : IO<R>

fun <R> performIO(block: suspend () -> R): Program<R> = perform(SuspendIO(block))

// Apply AFTER all other effect handlers have stripped their effects.
suspend fun <A> Program<A>.runIO(): Program<A> {
    var current: Program<A> = this
    while (true) {
        when (val c = current) {
            is Program.Done -> {
                return c
            }

            is Program.Suspended<*, *> -> {
                val suspended = c as Program.Suspended<Any?, A>
                val effect = suspended.effect
                if (effect is SuspendIO<*>) {
                    val result = (effect as SuspendIO<Any?>).thunk()
                    current = resume(suspended.pipeline, result)
                } else {
                    error(
                        "runIO() encountered unhandled effect: ${effect::class.simpleName}. " +
                            "Apply runIO() after all other effect handlers.",
                    )
                }
            }
        }
    }
}

fun <A> Program<A>.runIOBlocking(): Program<A> = runBlocking { runIO() }
