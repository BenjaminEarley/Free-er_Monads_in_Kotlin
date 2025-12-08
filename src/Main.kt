import effects.fail
import effects.get
import effects.isFraudulent
import effects.logError
import effects.logInfo
import effects.put
import effects.runFraudCheck
import effects.runKVStore
import effects.runLogger
import effects.runSafe

fun main() {
    // Initial State
    val database =
        mutableMapOf<String, Any>(
            "Alice" to 1000.0,
            "Bob" to 50.0,
        )

    println("""Initial Database State: $database""")

    println("--- Scenario 1: Successful Transfer ---")
    val programSuccess = transferMoney("Alice", "Bob", 100.0)

    // Composition: Safe( Audit( Fraud( DB( Program ) ) ) )
    val result1 =
        programSuccess
            .runKVStore(database) // Handle DB requests
            .runFraudCheck() // Handle Fraud requests
            .runLogger() // Handle Log requests
            .runSafe() // Handle Errors (Outer layer catches exceptions)

    assert(result1 is Program.Done)
    runInterpreter(result1)

    println("\n--- Database State After Tx 1 ---")
    println(database)

    println("\n--- Scenario 2: Insufficient Funds ---")
    val programFail = transferMoney("Bob", "Alice", 9999.0)

    val result2 =
        programFail
            .runKVStore(database)
            .runFraudCheck()
            .runLogger()
            .runSafe()

    assert(result2 is Program.Done)
    runInterpreter(result2)

    println("\n--- Scenario 3: Fraud Detection ---")
    val programFraud = transferMoney("Alice", "Bob", 6000.0)

    val result3 =
        programFraud
            .runKVStore(database)
            .runFraudCheck()
            .runLogger()
            .runSafe()

    assert(result3 is Program.Done)
    runInterpreter(result3)
}

fun transferMoney(
    from: String,
    to: String,
    amount: Double,
): Program<String> =
    logInfo("Request: Transfer $$amount from $from to $to")
        .flatMap {
            // 1. Security Check (External API)
            isFraudulent(amount, from)
        }.flatMap { isRisk ->
            if (isRisk) {
                fail("Security Alert: Fraud detected for account $from")
            } else {
                // 2. Read Source Balance (Database)
                get(from, 0.0)
            }
        }.flatMap { balance ->
            if (balance < amount) {
                // 3. Validation Logic
                logError("Insufficient funds in $from")
                    .flatMap { fail("Insufficient funds") }
            } else {
                // 4. Update Balances (Transactional Write)
                put(from, balance - amount)
                    .flatMap { get(to, 0.0) }
                    .flatMap { targetBal -> put(to, targetBal + amount) }
                    .flatMap {
                        logInfo("Success: Transferred $$amount. New Balance $from: $${balance - amount}")
                            .map { "TX_OK" }
                    }
            }
        }
