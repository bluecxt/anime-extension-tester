package dev.tester.ui

import dev.tester.model.ExpectedSnapshot

object ConsoleReporter {

    const val RESET = "\u001B[0m"
    const val GREEN = "\u001B[32m"
    const val RED = "\u001B[31m"
    const val YELLOW = "\u001B[33m"
    const val BLUE = "\u001B[34m"
    const val CYAN = "\u001B[36m"
    const val BOLD = "\u001B[1m"
    const val GRAY = "\u001B[90m"

    fun printSuiteHeader(suiteName: String, extensionFqn: String) {
        println()
        println("$BOLD$BLUE═══ Suite: $suiteName $GRAY($extensionFqn)$RESET")
    }

    fun printTestSuccess(testName: String, durationMs: Long) {
        println("  $GREEN✓$RESET $testName $GRAY(${durationMs}ms)$RESET")
    }

    fun printTestUpdated(testName: String, durationMs: Long) {
        println("  $YELLOW⟳$RESET $testName $YELLOW[SNAPSHOT UPDATED]$RESET $GRAY(${durationMs}ms)$RESET")
    }

    fun printTestFailure(testName: String, durationMs: Long, reason: String, expectedJson: String? = null, actualJson: String? = null) {
        println("  $RED✗$RESET $testName $GRAY(${durationMs}ms)$RESET")
        println("    $RED└─ Cause: $reason$RESET")
        if (expectedJson != null && actualJson != null) {
            println("    $YELLOW[Diff Snapshot]$RESET")
            println("      $RED- Expected: $expectedJson$RESET")
            println("      $GREEN+ Actual:   $actualJson$RESET")
        }
    }

    fun printSummary(total: Int, passed: Int, failed: Int, updated: Int, totalDurationMs: Long) {
        println()
        println("$BOLD════════════════════ SUMMARY ════════════════════$RESET")
        println("  Total tests:   $total")
        println("  Passed:        $GREEN$passed$RESET")
        if (updated > 0) {
            println("  Updated:       $YELLOW$updated$RESET")
        }
        if (failed > 0) {
            println("  Failed:        $RED$failed$RESET")
        }
        println("  Duration:      ${totalDurationMs}ms")
        println("$BOLD══════════════════════════════════════════════════$RESET")
        println()
    }
}
