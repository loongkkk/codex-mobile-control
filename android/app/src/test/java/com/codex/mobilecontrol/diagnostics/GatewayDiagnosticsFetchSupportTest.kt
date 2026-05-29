package com.codex.mobilecontrol.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayDiagnosticsFetchSupportTest {
    @Test
    fun `gateway diagnostics timeout becomes recoverable failure`() = runTest {
        val result = fetchGatewayDiagnosticsForBundle {
            withTimeout(1) {
                delay(10)
                "unreachable"
            }
        }

        assertTrue(result is GatewayDiagnosticsFetchResult.Failure)
        assertTrue((result as GatewayDiagnosticsFetchResult.Failure).error is TimeoutCancellationException)
    }

    @Test
    fun `lifecycle cancellation is still rethrown`() = runTest {
        val cancellation = CancellationException("activity destroyed")
        var thrown: Throwable? = null

        try {
            fetchGatewayDiagnosticsForBundle {
                throw cancellation
            }
        } catch (error: Throwable) {
            thrown = error
        }

        assertSame(cancellation, thrown)
    }
}
