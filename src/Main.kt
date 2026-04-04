import effects.auditFraudCheck
import effects.auditKVStore
import effects.fail
import effects.get
import effects.isFraudulent
import effects.logError
import effects.logInfo
import effects.put
import effects.runFraudCheck
import effects.runIOBlocking
import effects.runKVStore
import effects.runKVStoreAsync
import effects.runLogger
import effects.runSafe

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

    // Composition: Safe( Logger( Fraud( DB( Audit( Program ) ) ) ) )
    val result1 =
        programSuccess
            .auditKVStore() // Middleware: log every DB op, re-emit for real handler
            .auditFraudCheck() // Middleware: log suspicious transactions, re-emit
            .runKVStore(database) // Handler: execute DB operations
            .runFraudCheck() // Handler: decide fraud logic
            .runLogger() // Handler: print all logs (including audit logs)
            .runSafe() // Handler: catch errors

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

    // Scenario 4: Same program, but KVStore handled via IO effects (async-capable)
    println("\n--- Scenario 4: Async KVStore via IO Effect ---")
    val asyncDatabase =
        mutableMapOf<String, Any?>(
            "Alice" to 1000.0,
            "Bob" to 50.0,
        )

    val result4 =
        transferMoney("Alice", "Bob", 100.0)
            .runKVStoreAsync(asyncDatabase) // Pure interpret: replaces KVStore with IO effects
            .runFraudCheck()
            .runLogger()
            .runSafe()
            .runIOBlocking() // Handles all IO effects at the edge

    assert(result4 is Program.Done)
    runInterpreter(result4)
    println("\nAsync Database State: $asyncDatabase")
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
