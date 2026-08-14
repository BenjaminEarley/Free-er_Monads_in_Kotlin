@file:Suppress("UNCHECKED_CAST")

package ffree.effects

import ffree.Effect
import ffree.Program
import ffree.force
import ffree.perform
import ffree.resume
import ffree.trampolineMisuse
import java.util.concurrent.ArrayBlockingQueue
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

// Handlers that need real async I/O emit IO effects via performIO { }.
// A single suspend io() at the edge handles them all.
sealed interface IO<out R> : Effect<R>

data class SuspendIO<R>(
    val thunk: suspend () -> R,
) : IO<R>

fun <R> performIO(block: suspend () -> R): Program<R> = perform(SuspendIO(block))

// Terminal suspend runner. Apply AFTER all other effect handlers have stripped
// their effects: runs every IO thunk and returns the program's final value.
// Threading: thunk resumptions re-enter through the ContinuationInterceptor of the
// calling context, if any — under a coroutine dispatcher, interpretation stays on
// that dispatcher. In interceptor-free contexts (e.g. a bare suspend main), the
// first thunk that resumes on a foreign thread migrates the rest of interpretation
// onto it; use ioBlocking() there instead.
suspend fun <A> Program<A>.io(): A {
    var current: Program<A> = this
    while (true) {
        when (val c = current) {
            is Program.Done -> {
                return c.value
            }

            is Program.Defer -> current = c.force()

            is Program.Bounce -> trampolineMisuse()

            is Program.Suspended<*, *> -> {
                val suspended = c as Program.Suspended<Any?, A>
                val effect = suspended.effect
                if (effect is SuspendIO<*>) {
                    val result = (effect as SuspendIO<Any?>).thunk()
                    current = resume(suspended.pipeline, result)
                } else {
                    error(
                        "io() encountered unhandled effect: ${effect::class.simpleName}. " +
                            "Apply io() after all other effect handlers.",
                    )
                }
            }
        }
    }
}

// Blocking edge runner for interceptor-free contexts (a plain main, a thread you
// own): terminal, and every interpretation step runs on the calling thread. Each
// thunk is started in place; if it suspends, the caller blocks until the completion
// callback hands the Result across a queue — the transfer provides the
// happens-before edge, so handler state never needs synchronization, and thunk
// failures are rethrown here, on the caller's thread. The thunk itself may hop
// threads internally; only interpretation is confined.
fun <A> Program<A>.ioBlocking(): A {
    var current: Program<A> = this
    while (true) {
        when (val c = current) {
            is Program.Done -> {
                return c.value
            }

            is Program.Defer -> current = c.force()

            is Program.Bounce -> trampolineMisuse()

            is Program.Suspended<*, *> -> {
                val suspended = c as Program.Suspended<Any?, A>
                val effect = suspended.effect
                if (effect is SuspendIO<*>) {
                    val thunk = (effect as SuspendIO<Any?>).thunk
                    // Fresh box per thunk: a buggy thunk that resumes its continuation
                    // twice lands the second result in an orphaned box instead of
                    // poisoning the next thunk's handoff.
                    val box = ArrayBlockingQueue<Result<Any?>>(1)
                    val immediate =
                        thunk.startCoroutineUninterceptedOrReturn(
                            Continuation(EmptyCoroutineContext) { result -> box.put(result) },
                        )
                    val response = if (immediate === COROUTINE_SUSPENDED) box.take().getOrThrow() else immediate
                    current = resume(suspended.pipeline, response)
                } else {
                    error(
                        "ioBlocking() encountered unhandled effect: ${effect::class.simpleName}. " +
                            "Apply ioBlocking() after all other effect handlers.",
                    )
                }
            }
        }
    }
}
