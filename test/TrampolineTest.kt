/**
 * Trampoline stack-safety tests. Each scenario runs N = 100,000 effects (or binds)
 * through a differently shaped program/handler stack that could overflow the JVM
 * stack: direct-resume trampolining, the handle DSL's Done-path optimization,
 * effect forwarding past a non-matching handler, stateful interpretation, deferred
 * resumes via intercept, left-associated bind chains, deep non-tail recursion, and
 * long chains of pure binds.
 */

import ffree.Effect
import ffree.Program
import ffree.flatMap
import ffree.handle
import ffree.intercept
import ffree.interpret
import ffree.interpretS
import ffree.map
import ffree.perform
import ffree.program
import ffree.runOrThrow

sealed interface Counter<out R> : Effect<R>

data object Increment : Counter<Unit>

// An unrelated effect that forces forwarding
sealed interface Unrelated<out R> : Effect<R>

data object Noop : Unrelated<Unit>

const val N = 100_000 // Well beyond the ~5-10K stack limit

fun main() {
    println("=== Trampoline Stack-Safety Tests ===")
    println("Each test runs $N effects.")
    println()

    test("1. interpret (low-level, direct resume)") {
        // Build a program that performs N Increment effects using program { }
        val prog =
            program {
                var count = 0
                repeat(N) {
                    perform(Increment).bind()
                    count++
                }
                count
            }

        // Handle with raw interpret — resume called directly → trampoline
        prog
            .interpret<Counter<*>, Int> { op, resume ->
                when (op) {
                    is Increment -> resume(Unit)
                }
            }.runOrThrow()
    }

    test("2. handle (DSL, Done-path optimization)") {
        val prog =
            program {
                var count = 0
                repeat(N) {
                    perform(Increment).bind()
                    count++
                }
                count
            }

        // Handle with DSL — program { rule(op) } returns Done → resume called directly
        prog
            .handle<Counter<*>, Int> { op ->
                when (op) {
                    is Increment -> Unit
                }
            }.runOrThrow()
    }

    test("3. handle + forwarding (effect passes through a non-matching handler)") {
        val prog =
            program {
                var count = 0
                repeat(N) {
                    perform(Increment).bind()
                    count++
                }
                count
            }

        // Increment effects are forwarded past the Unrelated handler, then caught by Counter handler.
        // Each forwarded effect creates a Pipeline.Step with a deferred interpreterLoop call.
        // The outer handler's trampoline keeps the stack flat.
        prog
            .handle<Unrelated<*>, Int> { op ->
                when (op) {
                    is Noop -> Unit
                }
            }.handle<Counter<*>, Int> { op ->
                when (op) {
                    is Increment -> Unit
                }
            }.runOrThrow()
    }

    test("4. interpretS (stateful handler, extract final state)") {
        val prog =
            program {
                repeat(N) {
                    perform(Increment).bind()
                }
            }

        // Stateful handler: counts how many Increments were handled.
        // Uses interpretS directly with transformDone to extract the final count.
        val count =
            prog
                .interpretS<Counter<*>, Int, Unit, Int>(
                    initialState = 0,
                    transformDone = { s, _ -> Program.Done(s) },
                ) { s, op, resume ->
                    when (op) {
                        is Increment -> resume(s + 1, Unit)
                    }
                }.runOrThrow()

        // check, not assert: JVM assertions are disabled unless -ea is passed
        check(count == N) { "Expected $N, got $count" }
        count
    }

    test("5. intercept + handle (middleware chain, deferred resume)") {
        val prog =
            program {
                var count = 0
                repeat(N) {
                    perform(Increment).bind()
                    count++
                }
                count
            }

        // Middleware intercepts every Increment (re-emits via proceed), then handle consumes it.
        // proceed() calls perform(op) → Suspended path → deferred resume.
        // Stack safety comes from the deferred path resetting the stack.
        prog
            .intercept<Counter<*>, Int> { op, proceed ->
                when (op) {
                    is Increment -> proceed()
                }
            }.handle<Counter<*>, Int> { op ->
                when (op) {
                    is Increment -> Unit
                }
            }.runOrThrow()
    }

    test("6. left-associated flatMap chain + handle (pathological case)") {
        // Build a deeply left-associated chain — the worst case for both
        // the type-aligned queue AND the trampoline.
        var prog: Program<Int> = Program.Done(0)
        for (i in 1..N) {
            prog =
                prog.flatMap { count ->
                    perform(Increment).map { count + 1 }
                }
        }

        prog
            .handle<Counter<*>, Int> { op ->
                when (op) {
                    is Increment -> Unit
                }
            }.runOrThrow()
    }

    test("7. deep non-tail recursion (work after a suspending bind)") {
        // Each level does work AFTER a bind that suspends, so the remaining
        // pipeline is concatenated N levels deep. The unwind of the innermost
        // Done must stay iterative — nested resume closures would overflow
        // around depth ~5K.
        fun build(remaining: Int): Program<Int> =
            if (remaining == 0) {
                perform(Increment).map { 0 }
            } else {
                perform(Increment).flatMap { build(remaining - 1) }.map { it + 1 }
            }

        val result =
            build(N)
                .handle<Counter<*>, Int> { op ->
                    when (op) {
                        is Increment -> Unit
                    }
                }.runOrThrow()
        check(result == N) { "Expected $N, got $result" }
        result
    }

    test("8. long chains of pure (already-Done) binds") {
        // Consecutive binds of Done programs never suspend, so the interpreter
        // trampoline never sees them — buildProgram must consume them in a
        // loop, both at construction time (pure-only program) and after an
        // effectful bind (chain unrolls during interpretation).
        val pureOnly =
            program {
                var count = 0
                repeat(N) {
                    count += Program.Done(1).bind()
                }
                count
            }
        val constructed = pureOnly.runOrThrow()

        val afterEffect =
            program {
                perform(Increment).bind()
                var count = 0
                repeat(N) {
                    count += Program.Done(1).bind()
                }
                count
            }
        val interpreted =
            afterEffect
                .handle<Counter<*>, Int> { op ->
                    when (op) {
                        is Increment -> Unit
                    }
                }.runOrThrow()

        check(constructed == N && interpreted == N) { "Expected $N/$N, got $constructed/$interpreted" }
        interpreted
    }

    test("9. long flatMap chains onto a program{} head (Defer pending queue)") {
        // A Defer head (program{}, or any handled program) used to nest one
        // closure per bind, overflowing around depth ~10-20K when forced. Binds
        // now queue on the Defer's pending pipeline instead.
        var prog: Program<Int> = program { 0 }
        repeat(N) {
            prog = prog.flatMap { count -> Program.Done(count + 1) }
        }
        val first = prog.runOrThrow()
        val replayed = prog.runOrThrow() // pending queue is immutable — replay must match
        check(first == N && replayed == N) { "Expected $N/$N, got $first/$replayed" }
        first
    }

    test("10. long map chains onto a program{} head") {
        var prog: Program<Int> = program { 0 }
        repeat(N) {
            prog = prog.map { it + 1 }
        }
        val result = prog.runOrThrow()
        check(result == N) { "Expected $N, got $result" }
        result
    }

    test("11. long flatMap chains onto an already-handled head") {
        // Every applied handler returns a deferred program, so this is the same
        // shape as test 9 reached through the public handler API.
        var prog: Program<Int> =
            perform(Increment)
                .map { 0 }
                .interpret<Counter<*>, Int> { op, resume ->
                    when (op) {
                        is Increment -> resume(Unit)
                    }
                }
        repeat(N) {
            prog = prog.flatMap { count -> Program.Done(count + 1) }
        }
        val result = prog.runOrThrow()
        check(result == N) { "Expected $N, got $result" }
        result
    }

    println()
    println("All tests passed. No StackOverflowError.")
}

private fun test(
    name: String,
    block: () -> Any?,
) {
    print("  $name ... ")
    try {
        val start = System.nanoTime()
        val result = block()
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        println("OK  (${"%,.0f".format(elapsed)} ms, result=$result)")
    } catch (e: StackOverflowError) {
        println("FAILED — StackOverflowError!")
        throw e
    }
}
