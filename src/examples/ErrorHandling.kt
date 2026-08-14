/*
 * Error recovery and handler ordering.
 *
 * catchError abandons the failed program from the failure point and substitutes
 * a recovery program; a failure inside the recovery propagates outward.
 *
 * Handler ordering decides semantics, not just plumbing: the same program run
 * with state handled outside errors keeps the state written before the failure,
 * while errors handled outside state discard it — transactional rollback for
 * free, chosen at the call site.
 */
package ffree.examples

import ffree.Program
import ffree.effects.catchError
import ffree.effects.fail
import ffree.effects.memorize
import ffree.effects.memoryWithState
import ffree.effects.raise
import ffree.effects.recall
import ffree.effects.transactional
import ffree.program
import ffree.runOrThrow

fun errorRecoveryExample() {
    val risky: Program<String> =
        program {
            fail("primary source down").bind()
        }

    // Recovery replaces the failed program from the failure point
    val recovered =
        risky
            .catchError { reason -> Program.Done("fallback (was: $reason)") }
            .runOrThrow()
    println("recovered      = $recovered")

    // A failure inside the recovery escapes to the outer handler
    val rethrown: Result<String> =
        risky
            .catchError { fail("fallback also down") }
            .raise()
            .runOrThrow()
    println("rethrown       = ${rethrown.exceptionOrNull()?.message}")
}

fun handlerOrderingExample() {
    val prog: Program<Int> =
        program {
            memorize(1).bind()
            memorize(recall().bind() + 1).bind() // state = 2
            fail("boom").bind()
            recall().bind() // never reached
        }

    // Errors handled inside, state outside: the failure is local — state
    // written before the fail survives.
    val stateSurvives: Pair<Result<Int>, Int> =
        prog
            .raise()
            .memoryWithState(0)
            .runOrThrow()
    println("raise inside memory  = $stateSurvives")

    // State handled inside, errors outside: the failure is global — the
    // whole (value, state) result is rolled back.
    val stateRolledBack: Result<Pair<Int, Int>> =
        prog
            .memoryWithState(0)
            .raise()
            .runOrThrow()
    println("memory inside raise  = $stateRolledBack")
}

fun transactionalExample() {
    val depositThenFail: Program<String> =
        program {
            memorize(recall().bind() + 100).bind()
            fail("validation failed").bind()
            "unreachable"
        }

    // Unprotected (state outside errors): the partial write survives the failure
    val leaked = depositThenFail.raise().memoryWithState(500).runOrThrow()
    println("unprotected    = $leaked")

    // Transactional: writes hit a local copy; the commit never runs on failure
    val rolledBack = depositThenFail.transactional().raise().memoryWithState(500).runOrThrow()
    println("transactional  = $rolledBack")

    // On success, the transaction commits its final state downstream
    val deposit = program { memorize(recall().bind() + 100).bind(); "deposited" }
    val committed = deposit.transactional().raise().memoryWithState(500).runOrThrow()
    println("committed      = $committed")
}
