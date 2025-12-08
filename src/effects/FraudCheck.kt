package effects

import Effect
import Program
import flatMap
import interpret
import perform

fun <A> Program<A>.runFraudCheck(): Program<A> =
    interpret<FraudCheck<*>, A> { op, resume ->
        when (op) {
            is VerifyTransaction -> {
                val isSus = op.amount > 5000.0
                if (isSus) {
                    perform(Log("WARN", "Flagging transaction for review..."))
                        .flatMap { resume(isSus) }
                } else {
                    resume(isSus)
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
