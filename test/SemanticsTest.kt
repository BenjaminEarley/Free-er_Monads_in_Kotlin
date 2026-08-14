/**
 * Semantics tests: interpreter-rule contract enforcement and behaviors
 * not covered by the stack-safety tests in TrampolineTest.kt.
 */

import ffree.Effect
import ffree.Erased
import ffree.Program
import ffree.flatMap
import ffree.handle
import ffree.intercept
import ffree.interpret
import ffree.interpretS
import ffree.map
import ffree.perform
import ffree.program
import ffree.run
import ffree.runOrThrow
import ffree.effects.io
import ffree.effects.ioBlocking
import ffree.effects.memorize
import ffree.effects.memory
import ffree.effects.performIO
import ffree.effects.recall
import ffree.examples.accounting.effects.get
import ffree.examples.accounting.effects.kvStore
import ffree.examples.accounting.effects.put
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

sealed interface Ask<out R> : Effect<R>

data object AskInt : Ask<Int>

suspend fun main() {
    println("=== Semantics Tests ===")
    println()

    val askPlusOne = { perform(AskInt).flatMap { n -> Program.Done(n + 1) } }

    semTest("direct resume returned verbatim works") {
        val result =
            askPlusOne()
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> resume(41)
                    }
                }.runOrThrow()
        check(result == 42) { "Expected 42, got $result" }
    }

    semTest("transforming a direct resume result throws (not silent corruption)") {
        expectContractViolation {
            askPlusOne()
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> resume(41).map { it }
                    }
                }.runOrThrow()
        }
    }

    semTest("discarding a direct resume result throws (not a lost continuation)") {
        expectContractViolation {
            askPlusOne()
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> {
                            resume(41)
                            Program.Done(99)
                        }
                    }
                }.runOrThrow()
        }
    }

    semTest("calling resume directly twice throws (not last-branch-wins)") {
        expectContractViolation {
            askPlusOne()
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> {
                            resume(1)
                            resume(2)
                        }
                    }
                }.runOrThrow()
        }
    }

    semTest("same contract holds for interpretS") {
        expectContractViolation {
            askPlusOne()
                .interpretS<Ask<*>, Int, Int>(0) { s, op, resume ->
                    when (op) {
                        is AskInt -> resume(s, 41).map { it }
                    }
                }.runOrThrow()
        }
        expectContractViolation {
            askPlusOne()
                .interpretS<Ask<*>, Int, Int>(0) { s, op, resume ->
                    when (op) {
                        is AskInt -> {
                            resume(s, 41)
                            Program.Done(99)
                        }
                    }
                }.runOrThrow()
        }
        expectContractViolation {
            askPlusOne()
                .interpretS<Ask<*>, Int, Int>(0) { s, op, resume ->
                    when (op) {
                        is AskInt -> {
                            resume(s, 1)
                            resume(s, 2)
                        }
                    }
                }.runOrThrow()
        }
    }

    semTest("a stored resume stays usable after the rule returns, and is multi-shot") {
        // Short-circuit the interpretation but capture the continuation; hand-built
        // programs have pure pipelines, so the deferred continuation can be invoked
        // any number of times, each producing an independent result.
        var saved: ((Erased) -> Program<Int>)? = null
        val shortCircuited =
            askPlusOne()
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> {
                            saved = resume
                            Program.Done(-1)
                        }
                    }
                }.runOrThrow()
        check(shortCircuited == -1) { "Expected -1, got $shortCircuited" }

        val continuation = saved!!
        val first = continuation(5).runOrThrow()
        val second = continuation(10).runOrThrow()
        check(first == 6 && second == 11) { "Expected 6/11, got $first/$second" }
    }

    semTest("program{} values are replayable, pure descriptions") {
        val prog =
            program {
                val a = perform(AskInt).bind()
                val b = perform(AskInt).bind()
                a + b
            }

        fun runWith(n: Int): Int =
            prog
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> resume(n)
                    }
                }.runOrThrow()

        // Before Defer, the second run silently returned 11 (only the code after
        // the last bind re-ran, with stale locals from run 1).
        val r1 = runWith(1)
        val r2 = runWith(10)
        val r3 = runWith(1)
        check(r1 == 2 && r2 == 20 && r3 == 2) { "Expected 2/20/2, got $r1/$r2/$r3" }

        // Replay also works across different handler stacks (handle DSL).
        val h1 = prog.handle<Ask<*>, Int> { 5 }.runOrThrow()
        val h2 = prog.handle<Ask<*>, Int> { 50 }.runOrThrow()
        check(h1 == 10 && h2 == 100) { "Expected 10/100, got $h1/$h2" }
    }

    semTest("handler application is lazy; handled values are reusable descriptions") {
        var handlerRuns = 0
        val handled =
            program { perform(AskInt).bind() }
                .handle<Ask<*>, Int> {
                    handlerRuns++
                    3
                }
        check(handlerRuns == 0) { "Handler ran eagerly at application time" }

        val first = handled.runOrThrow()
        val second = handled.runOrThrow()
        check(first == 3 && second == 3 && handlerRuns == 2) {
            "Expected 3/3 with 2 handler runs, got $first/$second with $handlerRuns"
        }

        // A stored PARTIALLY-handled stack is also a reusable description: the
        // remaining effect can be handled and run any number of times.
        var audits = 0
        val partial =
            program { perform(AskInt).bind() }
                .intercept<Ask<*>, Int> { _, proceed ->
                    audits++
                    proceed()
                }
        repeat(2) {
            val r = partial.handle<Ask<*>, Int> { 7 }.runOrThrow()
            check(r == 7) { "Expected 7, got $r" }
        }
        check(audits == 2) { "Expected 2 audit runs, got $audits" }
    }

    semTest("binding a program{} returned inside flatMap works (Defer in pipeline)") {
        val prog =
            perform(AskInt).flatMap { n ->
                program { n + perform(AskInt).bind() }
            }
        val result =
            prog
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> resume(5)
                    }
                }.runOrThrow()
        check(result == 10) { "Expected 10, got $result" }
    }

    semTest("program{} blocks run per interpretation, not at construction") {
        var runs = 0
        val prog =
            program {
                runs++
                perform(AskInt).bind()
            }
        check(runs == 0) { "Block ran eagerly at construction" }

        repeat(2) {
            prog
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> resume(7)
                    }
                }.runOrThrow()
        }
        check(runs == 2) { "Expected 2 runs, got $runs" }
    }

    semTest("resuming one program{} suspension twice throws (single-shot coroutine)") {
        var saved: ((Erased) -> Program<Int>)? = null
        val shortCircuited =
            program { perform(AskInt).bind() + 1 }
                .interpret<Ask<*>, Int> { op, resume ->
                    when (op) {
                        is AskInt -> {
                            saved = resume
                            Program.Done(-1)
                        }
                    }
                }.runOrThrow()
        check(shortCircuited == -1) { "Expected -1, got $shortCircuited" }

        val continuation = saved!!
        val first = continuation(5).runOrThrow()
        check(first == 6) { "Expected 6, got $first" }
        try {
            continuation(7).runOrThrow()
            error("Expected an IllegalStateException on the second resumption")
        } catch (e: IllegalStateException) {
            check(e.message?.contains("single-shot") == true) {
                "Expected a single-shot IllegalStateException, got: ${e.message}"
            }
        }
    }

    semTest("not calling resume short-circuits (raise-style)") {
        val result =
            program {
                perform(AskInt).bind()
                error("unreachable: the continuation must not run")
            }.interpret<Ask<*>, Any?> { op, _ ->
                when (op) {
                    is AskInt -> Program.Done("stopped")
                }
            }.runOrThrow()
        check(result == "stopped") { "Expected 'stopped', got $result" }
    }

    semTest("kvStore rejects writes of the wrong type at the handler") {
        val db = mutableMapOf("Alice" to 1000.0)
        try {
            program { put("Alice", "not a number").bind() }.kvStore(db).runOrThrow()
            error("Expected an IllegalStateException for the mismatched Put")
        } catch (e: IllegalStateException) {
            check(e.message?.contains("KVStore") == true) { "Unexpected message: ${e.message}" }
        }
        check(db["Alice"] == 1000.0) { "Map was corrupted: $db" }
    }

    semTest("kvStore rejects reads whose expected type mismatches the stored value") {
        val db = mutableMapOf("k" to 1000.0)
        try {
            // Before the check, this silently coerced 1000.0 to Int and returned 1001.
            program { get("k", 0).bind() + 1 }.kvStore(db).runOrThrow()
            error("Expected an IllegalStateException for the mismatched Get")
        } catch (e: IllegalStateException) {
            check(e.message?.contains("KVStore") == true) { "Unexpected message: ${e.message}" }
        }
    }

    semTest("kvStore's read check skips structured values (no polymorphism false positive)") {
        val db = mutableMapOf<String, List<Int>>("k" to listOf(1, 2))
        val stored = program { get("k", emptyList<Int>()).bind() }.kvStore(db).runOrThrow()
        check(stored == listOf(1, 2)) { "Expected [1, 2], got $stored" }
    }

    semTest("kvStore returns a stored null instead of the default") {
        val db = mutableMapOf<String, Double?>("k" to null)
        val stored = program { get<Double?>("k", 5.0).bind() }.kvStore(db).runOrThrow()
        check(stored == null) { "Stored null was replaced by the default: $stored" }

        val absent = program { get<Double?>("missing", 5.0).bind() }.kvStore(db).runOrThrow()
        check(absent == 5.0) { "Absent key did not fall back to the default: $absent" }
    }

    semTest("handleS threads state through the DSL (memory)") {
        val prog =
            program {
                memorize(1).bind()
                val a = recall().bind()
                memorize(a + 10).bind()
                recall().bind()
            }
        val result = prog.memory(0).runOrThrow()
        check(result == 11) { "Expected 11, got $result" }

        val initialOnly = program { recall().bind() }.memory(42).runOrThrow()
        check(initialOnly == 42) { "Expected the initial state 42, got $initialOnly" }
    }

    semTest("intercept can observe and rewrite the downstream response") {
        val result =
            program { perform(AskInt).bind() }
                .intercept<Ask<*>, Int> { op, proceed ->
                    when (op) {
                        is AskInt -> (proceed() as Int) * 10
                    }
                }.handle<Ask<*>, Int> { 4 }
                .runOrThrow()
        check(result == 40) { "Expected 40, got $result" }
    }

    semTest("intercept can answer directly without forwarding") {
        val result =
            program { perform(AskInt).bind() }
                .intercept<Ask<*>, Int> { op, _ ->
                    when (op) {
                        is AskInt -> 99
                    }
                }.handle<Ask<*>, Int> { error("downstream handler must not run") }
                .runOrThrow()
        check(result == 99) { "Expected 99, got $result" }
    }

    semTest("interpret's transformDone maps the final value") {
        val result =
            program { perform(AskInt).bind() }
                .interpret<Ask<*>, Int, String>(
                    transformDone = { Program.Done("value=$it") },
                    rule = { op, resume ->
                        when (op) {
                            is AskInt -> resume(6)
                        }
                    },
                ).runOrThrow()
        check(result == "value=6") { "Expected value=6, got $result" }
    }

    semTest("run() captures the outcome as a Result") {
        val ok = program { perform(AskInt).bind() }.handle<Ask<*>, Int> { 7 }.run()
        check(ok == Result.success(7)) { "Expected success(7), got $ok" }

        val unhandled = program { perform(AskInt).bind() }.run()
        val failure = unhandled.exceptionOrNull()
        check(failure is IllegalStateException && failure.message?.contains("AskInt") == true) {
            "Expected a failure naming AskInt, got $unhandled"
        }
    }

    semTest("performIO thunks run at the io() edge, not before") {
        val order = mutableListOf<String>()
        val prog =
            program {
                val n = performIO {
                    order.add("io")
                    21
                }.bind()
                n * 2
            }
        order.add("before")
        val result = prog.io()
        check(result == 42 && order == listOf("before", "io")) { "Got $result, order=$order" }

        try {
            program { perform(AskInt).bind() }.io()
            error("Expected io() to reject a non-IO effect")
        } catch (e: IllegalStateException) {
            check(e.message?.contains("io()") == true) { "Unexpected message: ${e.message}" }
        }
    }

    semTest("ioBlocking keeps interpretation on the calling thread") {
        val caller = Thread.currentThread().name
        val prog =
            program {
                val before = performIO { Thread.currentThread().name }.bind()
                val async =
                    performIO {
                        suspendCoroutine { cont ->
                            thread(name = "test-callback") {
                                cont.resume(Thread.currentThread().name)
                            }
                        }
                    }.bind()
                val after = performIO { Thread.currentThread().name }.bind()
                Triple(before, async, after)
            }
        val (before, async, after) = prog.ioBlocking()
        check(before == caller && async == "test-callback" && after == caller) {
            "Interpretation escaped $caller: before=$before async=$async after=$after"
        }
    }

    semTest("ioBlocking rethrows async thunk failures on the calling thread") {
        try {
            program {
                performIO {
                    suspendCoroutine<Int> { cont ->
                        thread { cont.resumeWithException(IllegalStateException("async boom")) }
                    }
                }.bind()
            }.ioBlocking()
            error("Expected the async failure to be rethrown")
        } catch (e: IllegalStateException) {
            check(e.message == "async boom") { "Unexpected message: ${e.message}" }
        }
    }

    println()
    println("All semantics tests passed.")
}

private fun expectContractViolation(block: () -> Any?) {
    try {
        block()
    } catch (e: IllegalStateException) {
        check(e.message?.contains("rule contract") == true) {
            "Expected a rule-contract IllegalStateException, got: ${e.message}"
        }
        return
    }
    error("Expected an IllegalStateException, but nothing was thrown")
}

private suspend fun semTest(
    name: String,
    block: suspend () -> Unit,
) {
    print("  $name ... ")
    block()
    println("OK")
}
