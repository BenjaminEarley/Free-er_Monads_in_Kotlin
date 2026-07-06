# Free-er

Extensible Effects for Kotlin, based on [Freer Monads, More Extensible Effects](https://okmij.org/ftp/Haskell/extensible/more.pdf) by Oleg Kiselyov and Hiromi Ishii.

Programs are pure data structures that describe computations with effects. Effects are handled by composable interpreters that can be stacked, reordered, and swapped — separating *what* a program does from *how* it's executed. A `Program` value is a reusable description: it can be interpreted any number of times, under different handler stacks.

Running main.kt:
```sh
kotlinc -include-runtime -d /tmp/ffree.jar $(find src -name '*.kt') && java -jar /tmp/ffree.jar
```

Running the tests and the benchmark:
```sh
kotlinc -include-runtime -d /tmp/ffree-tests.jar $(find src test -name '*.kt')
java -cp /tmp/ffree-tests.jar TrampolineTestKt
java -cp /tmp/ffree-tests.jar SemanticsTestKt
java -cp /tmp/ffree-tests.jar BenchmarkKt
```

## Quick Example

### 1. Define an effect

```kotlin
import ffree.*

sealed interface Console<out R> : Effect<R>
data class Print(val msg: String) : Console<Unit>
data class ReadLine(val prompt: String) : Console<String>

fun print(msg: String) = perform(Print(msg))
fun readLine(prompt: String) = perform(ReadLine(prompt))
```

### 2. Write a program using the DSL

```kotlin
val greeter: Program<String> = program {
    print("What is your name?").bind()
    val name = readLine("> ").bind()
    print("Hello, $name!").bind()
    name
}
```

The `program { }` block uses `.bind()` to sequence effects. Api inspired by Arrow's Raise DSL.

### 3. Handle the effects

```kotlin
fun <A> Program<A>.console(): Program<A> =
    handle<Console<*>, A> { op ->
        when (op) {
            is Print -> println(op.msg)
            is ReadLine -> {
                kotlin.io.print(op.prompt)
                readln()
            }
        }
    }
```

The `handle` DSL auto-resumes with the block's return value. For effects that return `Unit` (like `Print`), just do the work. For effects that return a value (like `ReadLine`), return it. Note the qualified `kotlin.io.print`: the effect constructor `print` from step 1 shadows it, and calling *that* here would build a `Program` that is silently discarded instead of printing.

### 4. Run it

```kotlin
val name: String = greeter
    .console()
    .runOrThrow()  // extracts the value, or fails naming the unhandled effect
```

Because programs are pure descriptions, `greeter` can be run again — with the same handlers or different ones — and each interpretation replays it from the start. Handlers are lazy too: applying one builds a new description, and interpretation (with its side effects) runs when `.runOrThrow()` forces it.

## Core Concepts

| Concept | Description |
|---------|-------------|
| `Program<A>` | A pure description of a computation that produces `A` |
| `Effect<R>` | An interface for effect types that expect a response of type `R` |
| `perform(effect)` | Suspend the program, requesting an effect to be handled |
| `program { }` | DSL builder — use `.bind()` instead of `flatMap` chains |
| `handle` | Interpret an effect — return the response, resume is automatic |
| `intercept` | Middleware — observe an effect, call `proceed()` to re-emit it |
| `handleS` | Stateful handler — return `newState to response` |
| `interpret` | Low-level handler with explicit `resume` control |
| `.runOrThrow()` | Extract the final value, or fail naming the unhandled effect |
| `.run()` | Extract the outcome as a `Result` — success, or failure naming the unhandled effect |

The low-level `interpret` hands the rule a `resume` continuation with a contract: called directly (inside the rule), it must be called at most once and its result returned unchanged — the interpreter trampolines it for stack safety and throws on misuse. To transform or combine results, call `resume` from a genuinely deferred context (e.g. bind it inside a `program { }` block — `flatMap` on an already-`Done` program still runs eagerly), or just use `handle`/`intercept`.

## Async I/O

Handlers that need real I/O emit `IO` effects via `performIO { }`. A single `suspend` interpreter at the edge handles them all:

```kotlin
import ffree.effects.io
import ffree.effects.performIO

fun <A> Program<A>.runConsoleAsync(): Program<A> =
    handle<Console<*>, A> { op ->
        when (op) {
            is Print -> performIO { sendToRemoteLog(op.msg) }.bind()
            is ReadLine -> performIO { fetchInputFromApi() }.bind()
        }
    }

val name = greeter
    .runConsoleAsync()
    .io()             // suspend terminal: runs all IO effects, returns the value
```

`io()` is a terminal `suspend` function — call it from a coroutine, and apply it after every other handler so only `IO` effects remain. Threading: thunk resumptions re-enter through the calling context's `ContinuationInterceptor`, so under a coroutine dispatcher interpretation stays on that dispatcher. In interceptor-free contexts (a plain or bare `suspend` `main`) the first thunk that resumes on a foreign thread would migrate the rest of interpretation onto it — use the blocking edge runner there instead:

```kotlin
val name = greeter
    .runConsoleAsync()
    .ioBlocking()     // terminal: blocks the caller, interpretation never leaves this thread
```

`ioBlocking()` runs every interpretation step on the calling thread; async thunks may hop threads internally, but their results are handed back before interpretation continues, so handler state needs no synchronization.

## Middleware

`intercept` observes effects without consuming them. Call `proceed()` to re-emit the effect to a downstream handler:

```kotlin
fun <A> Program<A>.auditConsole(): Program<A> =
    intercept<Console<*>, A> { op, proceed ->
        when (op) {
            is Print -> { println("AUDIT: Print '${op.msg}'"); proceed() }
            is ReadLine -> { println("AUDIT: ReadLine"); proceed() }
        }
    }

val name = greeter
    .auditConsole()   // logs every console op, then re-emits
    .console()        // actually executes them
    .runOrThrow()
```

`proceed()` returns the downstream handler's response, so you can inspect or modify it:

```kotlin
fun <A> Program<A>.uppercaseConsole(): Program<A> =
    intercept<Console<*>, A> { op, proceed ->
        when (op) {
            is ReadLine -> {
                val input = proceed() as String   // get the downstream result
                input.uppercase()                  // modify it
            }
            else -> proceed()
        }
    }
```

A middleware can also perform *new* effects — the demo's `auditKVStore` emits `Log` effects. Handler order matters then: the handler for the emitted effects must sit downstream of the intercept. `prog.auditKVStore().kvStore(db).logger()` works; applying `logger()` before `auditKVStore()` leaves the audit logs unhandled.

## Errors & Exceptions

Failures are effects too: `fail(reason)` performs a `Raise` effect, and `raise()` materializes the program's outcome as a `Result` (see `effects/Error.kt` and the demo).

JVM exceptions are *not* part of the model: an exception thrown by a handler or an IO thunk propagates straight out of the interpretation. `try/catch` around `.bind()` inside `program { }` can never catch it, and `finally` blocks in the abandoned program do not run. Use effects (`Raise`/`Result`) for recoverable failures.

## Performance

The implementation uses a [type-aligned queue](https://okmij.org/ftp/Haskell/Reflection.html) for O(n) interpretation of both left- and right-associated bind chains (see `test/Benchmark.kt`), and a trampoline plus queue concatenation for stack safety — 100K+ effects through stacked handlers, deep non-tail recursion, and long sequential pure-bind chains all run on a constant stack (see `test/TrampolineTest.kt`). One known limit: deeply *nested* recursive `program { }` definitions that never perform an effect still consume stack proportional to nesting depth — recurse through an effect (or a hand-built lazy chain) for unbounded depth.

## License

[MIT](LICENSE)
