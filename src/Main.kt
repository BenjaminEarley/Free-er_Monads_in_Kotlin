package ffree

import ffree.examples.accounting.accountingExample
import ffree.examples.bindChainComparison
import ffree.examples.committedChoiceExample
import ffree.examples.errorRecoveryExample
import ffree.examples.generatorExample
import ffree.examples.handlerOrderingExample
import ffree.examples.nonDetExample
import ffree.examples.readerExample
import ffree.examples.transactionalExample

fun main() {
    accountingExample()

    println("\n--- Scenario 5: Left-Associated Bind Performance ---")
    bindChainComparison()

    println("\n--- Scenario 6: Error Recovery (catchError) ---")
    errorRecoveryExample()

    println("\n--- Scenario 7: Handler Ordering Decides Semantics ---")
    handlerOrderingExample()

    println("\n--- Scenario 8: Generators (reified continuations) ---")
    generatorExample()

    println("\n--- Scenario 9: Nondeterminism (multi-shot continuations) ---")
    nonDetExample()

    println("\n--- Scenario 10: Transactional State ---")
    transactionalExample()

    println("\n--- Scenario 11: Reader with local Overrides ---")
    readerExample()

    println("\n--- Scenario 12: Committed Choice (once / soft cut) ---")
    committedChoiceExample()
}
