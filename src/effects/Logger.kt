package ffree.effects

import ffree.Effect
import ffree.Program
import ffree.handle
import ffree.perform

enum class Severity(
    internal val color: String,
) {
    INFO("\u001B[32m"), // green
    WARN("\u001B[33m"), // yellow
    ERROR("\u001B[31m"), // red
    AUDIT("\u001B[36m"), // cyan
}

fun <A> Program<A>.logger(): Program<A> =
    handle<Logger<*>, A> { op ->
        when (op) {
            is Log -> {
                val reset = "\u001B[0m"
                println("${op.severity.color}[${op.severity}] ${op.msg}$reset")
            }
        }
    }

sealed interface Logger<out R> : Effect<R>

data class Log(
    val severity: Severity,
    val msg: String,
) : Logger<Unit>

fun logInfo(msg: String) = perform(Log(Severity.INFO, msg))

fun logWarn(msg: String) = perform(Log(Severity.WARN, msg))

fun logError(msg: String) = perform(Log(Severity.ERROR, msg))

fun logAudit(msg: String) = perform(Log(Severity.AUDIT, msg))
