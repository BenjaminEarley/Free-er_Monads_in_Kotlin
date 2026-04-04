@file:Suppress("UNCHECKED_CAST")

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume

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

    internal fun <A> buildProgram(): Program<A> =
        when (val s = state!!) {
            is State.Completed -> {
                Program.Done(s.value as A)
            }

            is State.Suspended -> {
                s.program.flatMap { value ->
                    s.continuation.resumeWith(Result.success(value))
                    buildProgram()
                }
            }
        }
}

fun <A> program(block: suspend ProgramScope.() -> A): Program<A> {
    val scope = ProgramScope()
    block.createCoroutineUnintercepted(receiver = scope, completion = scope).resume(Unit)
    return scope.buildProgram()
}
