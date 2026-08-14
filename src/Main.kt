package ffree

import ffree.examples.accounting.accountingExample
import ffree.examples.bindChainComparison

fun main() {
    accountingExample()

    println("\n--- Scenario 5: Left-Associated Bind Performance ---")
    bindChainComparison()
}
