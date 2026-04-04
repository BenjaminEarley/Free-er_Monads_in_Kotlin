package effects

import Effect
import Program
import handle
import intercept
import perform

// Pure handler: just decides if the transaction is fraudulent
fun <A> Program<A>.fraudCheck(): Program<A> =
    handle<FraudCheck<*>, A> { op ->
        when (op) {
            is VerifyTransaction -> op.amount > 5000.0
        }
    }

// Middleware: logs suspicious transactions without owning the fraud logic
fun <A> Program<A>.auditFraudCheck(): Program<A> =
    intercept<FraudCheck<*>, A> { op ->
        when (op) {
            is VerifyTransaction -> {
                val isSus = perform(op).bind()
                if (isSus) {
                    perform(Log("WARN", "Flagging transaction for review...")).bind()
                }
                isSus
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
