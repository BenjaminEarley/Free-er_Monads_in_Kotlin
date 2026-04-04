package effects

import Effect
import Program
import flatMap
import interpose
import interpret
import perform

// Pure handler: just decides if the transaction is fraudulent
fun <A> Program<A>.runFraudCheck(): Program<A> =
    interpret<FraudCheck<*>, A> { op, resume ->
        when (op) {
            is VerifyTransaction -> resume(op.amount > 5000.0)
        }
    }

// Middleware: logs suspicious transactions without owning the fraud logic
fun <A> Program<A>.auditFraudCheck(): Program<A> =
    interpose<FraudCheck<*>, A> { op, resume ->
        when (op) {
            is VerifyTransaction -> {
                perform(op).flatMap { isSus ->
                    if (isSus) {
                        perform(Log("WARN", "Flagging transaction for review..."))
                            .flatMap { resume(isSus) }
                    } else {
                        resume(isSus)
                    }
                }
            }
        }
    }

sealed interface FraudCheck<out R> : Effect<R>

data class VerifyTransaction(
    val amount: Double,
    val accountId: String,
) : FraudCheck<Boolean>

fun isFraudulent(
    amount: Double,
    accountId: String,
) = perform(VerifyTransaction(amount, accountId))
