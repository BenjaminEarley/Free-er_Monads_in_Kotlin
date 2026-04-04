# Free-er

Extensible Effects for Kotlin, based on [Freer Monads, More Extensible Effects](https://okmij.org/ftp/Haskell/extensible/more.pdf) by Oleg Kiselyov and Hiromi Ishii.

Programs are pure data structures that describe computations with effects. Effects are handled by composable interpreters that can be stacked, reordered, and swapped — separating *what* a program does from *how* it's executed.

## Quick Example

### 1. Define an effect

```kotlin
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
fun <A> Program<A>.runConsole(): Program<A> =
    handle<Console<*>, A> { op ->
        when (op) {
            is Print -> kotlin.io.println(op.msg)
            is ReadLine -> { kotlin.io.print(op.prompt); kotlin.io.readLine()!! }
        }
    }
```

The `handle` DSL auto-resumes with the block's return value. For effects that return `Unit` (like `Print`), just do the work. For effects that return a value (like `ReadLine`), return it.

### 4. Run it

```kotlin
val name: String = greeter
    .runConsole()
    .runOrThrow()  // extracts the value, or fails naming the unhandled effect
```

## Core Concepts

| Concept | Description |
|---------|-------------|
| `Program<A>` | A pure description of a computation that produces `A` |
| `Effect<R>` | An interface for effect types that expect a response of type `R` |
| `perform(effect)` | Suspend the program, requesting an effect to be handled |
| `program { }` | DSL builder — use `.bind()` instead of `flatMap` chains |
| `handle` | Interpret an effect — return the response, resume is automatic |
| `intercept` | Observe an effect without removing it (middleware) |
| `handleS` | Stateful handler — return `newState to response` |
| `interpret` / `interpose` | Low-level handlers with explicit `resume` control |
| `.runOrThrow()` | Extract the final value, or fail naming the unhandled effect |

## Async I/O

Handlers that need real I/O emit `IO` effects via `performIO { }`. A single `suspend` interpreter at the edge handles them all:

```kotlin
fun <A> Program<A>.runConsoleAsync(): Program<A> =
    handle<Console<*>, A> { op ->
        when (op) {
            is Print -> performIO { sendToRemoteLog(op.msg) }.bind()
            is ReadLine -> performIO { fetchInputFromApi() }.bind()
        }
    }

val name = greeter
    .runConsoleAsync()
    .ioBlocking()     // handles all IO effects (suspend boundary)
    .runOrThrow()
```

## Middleware

`intercept` observes effects without consuming them — the effect is re-emitted for a downstream handler:

```kotlin
fun <A> Program<A>.auditConsole(): Program<A> =
    intercept<Console<*>, A> { op ->
        when (op) {
            is Print -> { log("AUDIT: Print '${op.msg}'"); perform(op).bind() }
            is ReadLine -> { log("AUDIT: ReadLine"); perform(op).bind() }
        }
    }

val name = greeter
    .auditConsole()   // logs every console op, then re-emits
    .runConsole()     // actually executes them
    .runOrThrow()
```

## Performance

The implementation uses a [type-aligned queue](https://okmij.org/ftp/Haskell/Reflection.html) for O(n) interpretation of both left- and right-associated bind chains, and a trampoline for stack safety. See `test/Benchmark.kt`.
