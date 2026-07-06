@file:Suppress("UNCHECKED_CAST")

package ffree

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume

/**
 * Receiver scope of [program] blocks: sequence sub-programs with [bind]. Suspension is
 * restricted, so only Program binds — not arbitrary suspend calls — can occur inside.
 */
@RestrictsSuspension
class ProgramScope : Continuation<Any?> {
    override val context: CoroutineContext = EmptyCoroutineContext

    private sealed class State {
        class Suspended(
            val program: Program<Any?>,
            val continuation: Continuation<Any?>,
        ) : State()

        class Completed(
            val value: Any?,
        ) : State()
    }

    private var state: State? = null

    override fun resumeWith(result: Result<Any?>) {
        state = State.Completed(result.getOrThrow())
    }

    suspend fun <A> Program<A>.bind(): A =
        suspendCoroutineUninterceptedOrReturn { cont ->
            state = State.Suspended(this@bind as Program<Any?>, cont as Continuation<Any?>)
            COROUTINE_SUSPENDED
        }

    internal fun <A> buildProgram(): Program<A> {
        while (true) {
            when (val s = state!!) {
                is State.Completed -> {
                    return Program.Done(s.value as A)
                }

                is State.Suspended -> {
                    val program = s.program
                    if (program is Program.Done) {
                        // Pure bind: resume in place and keep looping. Recursing here
                        // (as the flatMap path below does) overflows the stack on long
                        // chains of already-Done binds.
                        s.continuation.resumeWith(Result.success(program.value))
                    } else {
                        // The coroutine behind this continuation is single-shot; guard
                        // against a second resumption, which would silently re-run only
                        // the code after the last bind with stale locals.
                        var consumed = false
                        return program.flatMap { value ->
                            check(!consumed) {
                                "program{} continuations are single-shot: this suspension was " +
                                    "already resumed. Multi-shot resumption requires hand-built " +
                                    "programs (perform/flatMap chains)."
                            }
                            consumed = true
                            s.continuation.resumeWith(Result.success(value))
                            buildProgram()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Build a [Program] in direct style: `.bind()` each sub-program instead of chaining
 * flatMap. The block is captured, not run — each interpretation forces it afresh, so
 * program{} values are pure, replayable descriptions that can be interpreted any number
 * of times, under different handler stacks.
 */
fun <A> program(block: suspend ProgramScope.() -> A): Program<A> =
    Program.Defer {
        val scope = ProgramScope()
        block.createCoroutineUnintercepted(receiver = scope, completion = scope).resume(Unit)
        scope.buildProgram()
    }

/** Interpret with auto-resume: the block's return value is the effect's response. */
inline fun <reified E : Effect<*>, A> Program<A>.handle(noinline rule: suspend ProgramScope.(E) -> Any?): Program<A> =
    interpret<E, A> { op, resume ->
        when (val result = program { rule(op) }.force()) {
            is Program.Done -> resume(result.value)
            is Program.Suspended<*, *> -> result.flatMap { response -> resume(response) }
            else -> error("unreachable: force() leaves only Done or Suspended")
        }
    }

/**
 * Middleware (the paper's interpose): observe an effect without consuming it. proceed()
 * re-emits the effect to a downstream handler and returns its response — call it where
 * the effect should execute, and rewrite (or skip) the result as needed.
 */
inline fun <reified E : Effect<*>, A> Program<A>.intercept(
    noinline rule: suspend ProgramScope.(effect: E, proceed: suspend ProgramScope.() -> Any?) -> Any?,
): Program<A> =
    interpret<E, A> { op, resume ->
        val proceed: suspend ProgramScope.() -> Any? = { perform(op).bind() }
        when (val result = program { rule(op, proceed) }.force()) {
            is Program.Done -> resume(result.value)
            is Program.Suspended<*, *> -> result.flatMap { response -> resume(response) }
            else -> error("unreachable: force() leaves only Done or Suspended")
        }
    }

/** Stateful [handle]: the block receives the current state and returns `newState to response`. */
inline fun <reified E : Effect<*>, S, A> Program<A>.handleS(
    initialState: S,
    noinline rule: suspend ProgramScope.(S, E) -> Pair<S, Any?>,
): Program<A> =
    interpretS<E, S, A>(initialState) { s, op, resume ->
        when (val result = program { rule(s, op) }.force()) {
            is Program.Done -> {
                val (newState, response) = result.value
                resume(newState, response)
            }

            is Program.Suspended<*, *> -> {
                result.flatMap { (newState, response) -> resume(newState, response) }
            }

            else -> error("unreachable: force() leaves only Done or Suspended")
        }
    }
