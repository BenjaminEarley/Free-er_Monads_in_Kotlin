import effects.auditFraudCheck
import effects.auditKVStore
import effects.fail
import effects.fraudCheck
import effects.get
import effects.ioBlocking
import effects.isFraudulent
import effects.kvStore
import effects.logError
import effects.logInfo
import effects.logger
import effects.put
import effects.raise
import effects.runKVStoreAsync

fun main() {
    // Initial State
    val database =
        mutableMapOf(
            "Alice" to 1000.0,
            "Bob" to 50.0,
        )

    println("""Initial Database State: $database""")

    println("--- Scenario 1: Successful Transfer ---")
    val programSuccess = transferMoney("Alice", "Bob", 100.0)

    val result1: Result<String> =
        programSuccess
            .auditKVStore() // Middleware: log every DB op, re-emit for real handler
            .auditFraudCheck() // Middleware: log suspicious transactions, re-emit
            .kvStore(database) // Handler: execute DB operations
            .fraudCheck() // Handler: decide fraud logic
            .logger() // Handler: print all logs (including audit logs)
            .raise() // Handler: catch errors
            .runOrThrow()

    println(result1)
    println("\n--- Database State After Tx 1 ---")
    println(database)

    println("\n--- Scenario 2: Insufficient Funds ---")
    val programFail = transferMoney("Bob", "Alice", 9999.0)

    val result2 =
        programFail
            .auditKVStore()
            .auditFraudCheck()
            .kvStore(database)
            .fraudCheck()
            .logger()
            .raise()
            .runOrThrow()

    println(result2)
    println("\n--- Scenario 3: Fraud Detection ---")
    val programFraud = transferMoney("Alice", "Bob", 6000.0)

    val result3 =
        programFraud
            .auditKVStore()
            .auditFraudCheck()
            .kvStore(database)
            .fraudCheck()
            .logger()
            .raise()
            .runOrThrow()

    println(result3)

    println("\n--- Scenario 4: Async KVStore via IO Effect ---")
    val asyncDatabase =
        mutableMapOf(
            "Alice" to 1000.0,
            "Bob" to 50.0,
        )

    val result4 =
        transferMoney("Alice", "Bob", 100.0)
            .auditKVStore()
            .auditFraudCheck()
            .runKVStoreAsync(asyncDatabase) // KVStore with IO effects
            .kvStore(database)
            .fraudCheck()
            .logger()
            .raise()
            .ioBlocking() // Handles all IO effects at the edge
            .runOrThrow()

    println(result4)
    println("Async Database State: $asyncDatabase")
}

fun transferMoney(
    from: String,
    to: String,
    amount: Double,
): Program<String> =
    program {
        logInfo("Request: Transfer $$amount from $from to $to").bind()

        // 1. Security Check (External API)
        val isRisk = isFraudulent(amount, from).bind()
        if (isRisk) {
            fail("Security Alert: Fraud detected for account $from").bind()
        }

        // 2. Read Source Balance (Database)
        val balance = get(from, 0.0).bind()

        if (balance < amount) {
            // 3. Validation Logic
            logError("Insufficient funds in $from").bind()
            fail("Insufficient funds").bind()
        }

        // 4. Update Balances (Transactional Write)
        put(from, balance - amount).bind()
        val targetBal = get(to, 0.0).bind()
        put(to, targetBal + amount).bind()
        logInfo("Success: Transferred $$amount. New Balance $from: $${balance - amount}").bind()
        "TX_OK"
    }

private fun println(result: Result<String>) =
    result.fold(
        onSuccess = { println(">> Final Result: $it") },
        onFailure = { println(">> Pipeline Failed: ${it.message}") },
    )
