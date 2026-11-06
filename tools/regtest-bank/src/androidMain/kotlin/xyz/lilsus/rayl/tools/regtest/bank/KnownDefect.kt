package xyz.lilsus.rayl.tools.regtest.bank

/**
 * A check that fails because of an open defect (qa/test-plan.md §10). It passes while [block]
 * fails, and fails once [block] passes, so the fix removes the marker and the check becomes a
 * normal regression test.
 */
inline fun knownDefect(issue: String, block: () -> Unit) {
    try {
        block()
    } catch (_: AssertionError) {
        return
    }
    throw AssertionError("$issue seems fixed: remove knownDefect(\"$issue\") and close the issue")
}
