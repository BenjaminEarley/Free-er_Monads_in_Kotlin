/*
 * Freer Monads & Extensible Effects.
 * A Kotlin implementation based on "Freer Monads, More Extensible Effects" by Kiselyov & Ishii.
 * https://okmij.org/ftp/Haskell/extensible/more.pdf
 */

@file:Suppress("UNCHECKED_CAST")

package ffree

/** The "Existential" type. We use Any? because of type erasure. */
typealias Erased = Any?

// interpret: handle and remove an effect
inline fun <reified E : Effect<*>, A, B> Program<A>.interpret(
    noinline transformDone: (A) -> Program<B>,
    noinline rule: (E, (Erased) -> Program<B>) -> Program<B>,
): Program<B> = lazyInterpret(this, E::class.java, transformDone, rule)

inline fun <reified E : Effect<*>, A> Program<A>.interpret(noinline rule: (E, (Erased) -> Program<A>) -> Program<A>): Program<A> =
    interpret(
        transformDone = { Program.Done(it) },
        rule = rule,
    )

/**
 * Handle and remove an effect, threading state [S] through every step (the paper's
 * handleRelayS). The same rule contract and lazy application as [interpret] apply.
 */
inline fun <reified E : Effect<*>, S, A, B> Program<A>.interpretS(
    initialState: S,
    noinline transformDone: (S, A) -> Program<B>,
    noinline rule: (S, E, (S, Erased) -> Program<B>) -> Program<B>,
): Program<B> = lazyInterpretS(this, initialState, E::class.java, transformDone, rule)

inline fun <reified E : Effect<*>, S, A> Program<A>.interpretS(
    initialState: S,
    noinline rule: (S, E, (S, Erased) -> Program<A>) -> Program<A>,
): Program<A> =
    interpretS(
        initialState = initialState,
        transformDone = { _, a -> Program.Done(a) },
        rule = rule,
    )

/** Extract the final value of a fully handled program, or throw naming the unhandled effect. */
fun <A> Program<A>.runOrThrow(): A =
    when (val forced = force()) {
        is Program.Done -> {
            forced.value
        }

        is Program.Suspended<*, *> -> {
            error("Unhandled effect: ${forced.effect}")
        }

        is Program.Defer -> {
            // unreachable: force() removes Defer
            forced.runOrThrow()
        }

        is Program.Bounce -> {
            trampolineMisuse()
        }
    }

/**
 * Run a fully handled program, capturing the outcome as a [Result]: the final value on
 * success, or an [IllegalStateException] naming the unhandled effect on failure.
 */
fun <A> Program<A>.run(): Result<A> =
    when (val forced = force()) {
        is Program.Done -> {
            Result.success(forced.value)
        }

        is Program.Suspended<*, *> -> {
            Result.failure(IllegalStateException("Unhandled effect: ${forced.effect}"))
        }

        is Program.Defer -> {
            // unreachable: force() removes Defer
            forced.run()
        }

        is Program.Bounce -> {
            trampolineMisuse()
        }
    }

// The "Fast Type-Aligned Queue"
// https://okmij.org/ftp/Haskell/Reflection.html
// This represents the "Pipeline" of functions waiting to be executed.
internal sealed class Pipeline<in Input, out Output> {
    // A single function step
    class Step<Input, Output>(
        val fn: (Input) -> Program<Output>,
    ) : Pipeline<Input, Output>()

    // Concatenation of two pipelines
    class Join(
        val left: Pipeline<Erased, Erased>,
        val right: Pipeline<Erased, Erased>,
    ) : Pipeline<Erased, Erased>()

    // O(1) Append: Adds a step to the end of the pipeline
    fun <NewOutput> then(fn: (Output) -> Program<NewOutput>): Pipeline<Input, NewOutput> {
        val nextStep = Step(fn) as Pipeline<Erased, Erased>
        val current = this as Pipeline<Erased, Erased>
        return Join(current, nextStep) as Pipeline<Input, NewOutput>
    }
}

/**
 * A request that expects a response of type [R]. Effect families are the open union of
 * everything a [Program] can ask its handlers to do.
 */
interface Effect<out R>

/**
 * A pure, reusable description of a computation producing [A]. Build one with [program]
 * or [perform]/[flatMap], then apply handlers ([handle], [intercept], [handleS],
 * [interpret]) and extract the value with [runOrThrow] or [run].
 */
sealed class Program<out A> {
    // Pure: The program is finished with a final value
    data class Done<out A>(
        val value: A,
    ) : Program<A>()

    // Impure: The program is paused (Suspended), waiting for a Request to be handled
    class Suspended<Response, out A> internal constructor(
        internal val effect: Effect<Response>,
        internal val pipeline: Pipeline<Response, A>, // The continuation logic
    ) : Program<A>()

    // Lazy: The program is a description that is (re)built on demand. Interpreting
    // forces the thunk, so the same Program value can be interpreted any number of
    // times — each interpretation gets a fresh underlying structure. This is what
    // makes program{} blocks pure, reusable descriptions despite being backed by
    // one-shot coroutine state machines.
    internal class Defer<out A>(
        val thunk: () -> Program<A>,
    ) : Program<A>()

    // Internal trampoline marker: returned by a direct resume() call inside the
    // interpreter loops. Never a real program value — any attempt to use it as one
    // is a broken rule contract (see interpret) and fails with trampolineMisuse().
    internal data object Bounce : Program<Nothing>()

    companion object {
        val DONE_UNIT: Program<Unit> = Done(Unit)
    }
}

// Unwrap Defer nodes until a concrete Done/Suspended program emerges.
@PublishedApi
internal tailrec fun <A> Program<A>.force(): Program<A> =
    when (this) {
        is Program.Defer -> thunk().force()
        else -> this
    }

// Lazy entry points for interpret/interpretS: applying a handler builds a description;
// the interpreter loop runs when a terminal operation forces it.
@PublishedApi
internal fun <Target : Effect<*>, A, B> lazyInterpret(
    initialProgram: Program<A>,
    targetClass: Class<Target>,
    transformDone: (A) -> Program<B>,
    rule: (Target, (Erased) -> Program<B>) -> Program<B>,
): Program<B> = Program.Defer { interpreterLoop(initialProgram, targetClass, transformDone, rule) }

@PublishedApi
internal fun <Target : Effect<*>, S, A, B> lazyInterpretS(
    initialProgram: Program<A>,
    initialState: S,
    targetClass: Class<Target>,
    transformDone: (S, A) -> Program<B>,
    rule: (S, Target, (S, Erased) -> Program<B>) -> Program<B>,
): Program<B> = Program.Defer { interpreterLoopS(initialProgram, initialState, targetClass, transformDone, rule) }

internal fun trampolineMisuse(): Nothing =
    throw IllegalStateException(
        "Broken rule contract: the Program returned by a direct resume() call is an internal " +
            "trampoline marker, not a real value — return it from the rule unchanged. To transform " +
            "or combine continuation results, call resume from a genuinely deferred context " +
            "instead (e.g. bind it inside a program{} block).",
    )

fun <A, B> Program<A>.flatMap(f: (A) -> Program<B>): Program<B> =
    when (this) {
        is Program.Done -> {
            f(this.value)
        }

        is Program.Suspended<*, *> -> {
            val suspended = this as Program.Suspended<Erased, A>
            Program.Suspended(suspended.effect, suspended.pipeline.then(f))
        }

        is Program.Defer -> {
            val deferred = this
            Program.Defer { deferred.thunk().flatMap(f) }
        }

        is Program.Bounce -> {
            trampolineMisuse()
        }
    }

fun <A, B> Program<A>.map(f: (A) -> B): Program<B> =
    when (this) {
        is Program.Done -> {
            Program.Done(f(this.value))
        }

        is Program.Suspended<*, *> -> {
            val suspended = this as Program.Suspended<Erased, A>
            Program.Suspended(suspended.effect, suspended.pipeline.then { Program.Done(f(it)) })
        }

        is Program.Defer -> {
            val deferred = this
            Program.Defer { deferred.thunk().map(f) }
        }

        is Program.Bounce -> {
            trampolineMisuse()
        }
    }

private val IDENTITY_STEP =
    Pipeline.Step<Erased, Erased> {
        if (it == Unit) Program.DONE_UNIT else Program.Done(it)
    }

/** Perform an effect: a one-step program that suspends on [effect] and produces its response. */
fun <R> perform(effect: Effect<R>): Program<R> = Program.Suspended(effect, IDENTITY_STEP as Pipeline<R, R>)

// The Virtual Machine
// This function advances the pipeline by one step.
internal tailrec fun <A, B> resume(
    pipeline: Pipeline<A, B>,
    input: A,
): Program<B> =
    when (pipeline) {
        is Pipeline.Step -> {
            pipeline.fn(input)
        }

        is Pipeline.Join -> {
            val left = pipeline.left
            val right = pipeline.right

            if (left is Pipeline.Step) {
                // Left is a single step: Run it.
                val leftStep = left as Pipeline.Step<A, Erased>

                when (val result = leftStep.fn(input).force()) {
                    is Program.Done -> {
                        // Step finished cleanly: Feed result into the Right side
                        resume(right as Pipeline<Erased, B>, result.value)
                    }

                    is Program.Defer -> {
                        error("unreachable: force() removes Defer nodes")
                    }

                    is Program.Bounce -> {
                        trampolineMisuse()
                    }

                    is Program.Suspended<*, *> -> {
                        // Step suspended: We must attach the Right side to the new suspension.
                        // Concatenate the queues directly instead of wrapping 'right' in a
                        // closure that calls resume: nested closures grow the stack O(depth)
                        // for non-tail-recursive programs; a Join keeps the unwind iterative
                        // via the rotation above.
                        val suspended = result as Program.Suspended<Erased, Erased>
                        val newQueue = Pipeline.Join(suspended.pipeline, right)
                        Program.Suspended(suspended.effect, newQueue) as Program<B>
                    }
                }
            } else {
                // to compare traditional implementation in benchmark test
                // resume(Pipeline.Join(left, right) as Pipeline<A, B>, input)

                // Left is a Join: Rotate Right to maintain performance guarantees.
                // ( (A + B) + C ) -> ( A + (B + C) )
                val leftJoin = left as Pipeline.Join
                val newLeft = leftJoin.left
                val newRight = Pipeline.Join(leftJoin.right, right)
                resume(Pipeline.Join(newLeft, newRight) as Pipeline<A, B>, input)
            }
        }
    }

internal fun directResumeCalledTwice(): Nothing =
    throw IllegalStateException(
        "Broken rule contract: resume() may be called at most once directly from a rule. " +
            "For multi-shot resumption, call resume from a genuinely deferred context " +
            "(e.g. bind it inside a program{} block).",
    )

internal fun directResumeDiscarded(): Nothing =
    throw IllegalStateException(
        "Broken rule contract: the rule called resume() directly but returned a different " +
            "Program — the resumed continuation would be silently lost. Return the result of " +
            "resume() unchanged, or do not call resume() at all (to short-circuit).",
    )

// Stack-safe Generic Interpreter Loop
// Uses a trampoline: when a rule calls resume() directly and returns the result,
// the continuation returns the internal Bounce marker instead of recursing. The
// while-loop detects it and iterates. When resume() is deferred (e.g. inside flatMap),
// the continuation falls back to normal recursion — which is safe since the stack
// resets at the deferral point. Violations of the rule contract (see interpret)
// fail with IllegalStateException instead of corrupting silently.
@PublishedApi
internal fun <Target : Effect<*>, A, B> interpreterLoop(
    initialProgram: Program<A>,
    targetClass: Class<Target>,
    transformDone: (A) -> Program<B>, // Logic for "Pure" values (A -> B)
    rule: (Target, (Erased) -> Program<B>) -> Program<B>, // Logic for "Impure" effects
): Program<B> {
    var program: Program<A> = initialProgram

    while (true) {
        when (val current = program) {
            is Program.Done -> {
                return transformDone(current.value)
            }

            is Program.Defer -> {
                program = current.thunk()
            }

            is Program.Bounce -> {
                trampolineMisuse()
            }

            is Program.Suspended<*, *> -> {
                val suspended = current as Program.Suspended<Erased, A>
                val effect = suspended.effect
                val pipeline = suspended.pipeline

                if (targetClass.isInstance(effect)) {
                    // MATCH: We found the effect we are looking for.
                    var trampolineNext: Program<A>? = null
                    var direct = true

                    val result =
                        rule(effect as Target) { response ->
                            if (direct && trampolineNext != null) directResumeCalledTwice()
                            val next = resume(pipeline, response)
                            if (direct) {
                                // Called directly by the rule — trampoline
                                trampolineNext = next
                                Program.Bounce
                            } else {
                                // Called from a deferred context (e.g. inside flatMap) — recurse normally
                                interpreterLoop(next, targetClass, transformDone, rule)
                            }
                        }

                    direct = false // Any future calls to this continuation are deferred

                    val next = trampolineNext
                    if (result is Program.Bounce) {
                        program = next ?: trampolineMisuse()
                        continue
                    }
                    if (next != null) directResumeDiscarded()
                    return result
                } else {
                    // NO MATCH: Relay the effect.
                    // We construct a new step that waits for the result, then recurses.
                    val forwardedPipeline =
                        Pipeline.Step { response: Erased ->
                            interpreterLoop(resume(pipeline, response), targetClass, transformDone, rule)
                        }
                    return Program.Suspended(effect, forwardedPipeline)
                }
            }
        }
    }
}

// Stack-safe Stateful Interpreter Loop (paper's handleRelayS)
// Same trampoline as interpreterLoop, but threads state S through every step.
@PublishedApi
internal fun <Target : Effect<*>, S, A, B> interpreterLoopS(
    initialProgram: Program<A>,
    initialState: S,
    targetClass: Class<Target>,
    transformDone: (S, A) -> Program<B>,
    rule: (S, Target, (S, Erased) -> Program<B>) -> Program<B>,
): Program<B> {
    var program: Program<A> = initialProgram
    var state = initialState

    while (true) {
        when (val current = program) {
            is Program.Done -> {
                return transformDone(state, current.value)
            }

            is Program.Defer -> {
                program = current.thunk()
            }

            is Program.Bounce -> {
                trampolineMisuse()
            }

            is Program.Suspended<*, *> -> {
                val suspended = current as Program.Suspended<Erased, A>
                val effect = suspended.effect
                val pipeline = suspended.pipeline

                if (targetClass.isInstance(effect)) {
                    var trampolineNext: Program<A>? = null
                    var trampolineState: Any? = null
                    var direct = true

                    val result =
                        rule(state, effect as Target) { newState, response ->
                            if (direct && trampolineNext != null) directResumeCalledTwice()
                            val next = resume(pipeline, response)
                            if (direct) {
                                trampolineNext = next
                                trampolineState = newState
                                Program.Bounce
                            } else {
                                interpreterLoopS(next, newState, targetClass, transformDone, rule)
                            }
                        }

                    direct = false

                    val next = trampolineNext
                    if (result is Program.Bounce) {
                        program = next ?: trampolineMisuse()
                        state = trampolineState as S
                        continue
                    }
                    if (next != null) directResumeDiscarded()
                    return result
                } else {
                    val s = state // capture for lambda
                    val forwardedPipeline =
                        Pipeline.Step { response: Erased ->
                            interpreterLoopS(resume(pipeline, response), s, targetClass, transformDone, rule)
                        }
                    return Program.Suspended(effect, forwardedPipeline)
                }
            }
        }
    }
}
