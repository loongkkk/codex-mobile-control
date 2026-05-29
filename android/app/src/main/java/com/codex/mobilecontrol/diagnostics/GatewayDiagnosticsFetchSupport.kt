package com.codex.mobilecontrol.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

sealed class GatewayDiagnosticsFetchResult {
    data class Success(val json: String) : GatewayDiagnosticsFetchResult()
    data class Failure(val error: Throwable) : GatewayDiagnosticsFetchResult()
}

suspend fun fetchGatewayDiagnosticsForBundle(
    fetch: suspend () -> String
): GatewayDiagnosticsFetchResult {
    return try {
        GatewayDiagnosticsFetchResult.Success(fetch())
    } catch (error: TimeoutCancellationException) {
        GatewayDiagnosticsFetchResult.Failure(error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        GatewayDiagnosticsFetchResult.Failure(error)
    }
}
