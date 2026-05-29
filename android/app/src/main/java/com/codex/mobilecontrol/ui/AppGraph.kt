package com.codex.mobilecontrol.ui

import android.content.Context
import com.codex.mobilecontrol.data.AndroidGatewaySessionStore
import com.codex.mobilecontrol.data.ThreadRepository
import com.codex.mobilecontrol.network.GatewayApiClient
import com.codex.mobilecontrol.network.OkHttpGatewaySseClient
import com.codex.mobilecontrol.network.OkHttpGatewaySocketClient

object AppGraph {
    private var repositoryInstance: ThreadRepository? = null

    var repositoryFactory: (Context) -> ThreadRepository = { context ->
        repositoryInstance ?: ThreadRepository(
            api = GatewayApiClient(),
            sseClient = OkHttpGatewaySseClient(),
            sessionStore = AndroidGatewaySessionStore(context.applicationContext),
            realtimeClient = OkHttpGatewaySocketClient()
        ).also { repositoryInstance = it }
    }

    fun resetForTests() {
        repositoryInstance = null
    }
}
