package effects

import Effect
import Program
import handle
import perform

fun <A> Program<A>.logger(): Program<A> =
    handle<Logger<*>, A> { op ->
        when (op) {
            is Log -> {
                val color = if (op.severity == "ERROR") "\u001B[31m" else "\u001B[32m"
                val reset = "\u001B[0m"
                println("$color[${op.severity}] ${op.msg}$reset")
            }
        }
    }

sealed interface Logger<out R> : Effect<R>

data class Log(
    val severity: String,
    val msg: String,
) : Logger<Unit>

fun logInfo(msg: String) = perform(Log("INFO", msg))

fun logError(msg: String) = perform(Log("ERROR", msg))
