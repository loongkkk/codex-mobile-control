package com.codex.mobilecontrol.data

import com.codex.mobilecontrol.GatewayConfig
import com.codex.mobilecontrol.model.ThreadDetail
import com.codex.mobilecontrol.network.GatewayApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ThreadDetailSnapshotSource {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<SnapshotKey, CompletableDeferred<ThreadDetail>>()

    suspend fun fetch(
        api: GatewayApi,
        config: GatewayConfig,
        threadId: String
    ): ThreadDetail {
        val key = snapshotKey(api, config, threadId)
        val existing = mutex.withLock { inFlight[key] }
        if (existing != null) {
            return existing.await()
        }

        val current = CompletableDeferred<ThreadDetail>()
        val active = mutex.withLock {
            inFlight[key]?.let { return@withLock it }
            inFlight[key] = current
            current
        }
        if (active !== current) {
            return active.await()
        }

        try {
            val detail = api.getThreadDetail(config, threadId)
            current.complete(detail)
            return detail
        } catch (error: Throwable) {
            current.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock {
                if (inFlight[key] === current) {
                    inFlight.remove(key)
                }
            }
        }
    }

    private fun snapshotKey(
        api: GatewayApi,
        config: GatewayConfig,
        threadId: String
    ): SnapshotKey {
        return SnapshotKey(
            api = api,
            url = config.url.trimEnd('/'),
            token = config.token,
            threadId = threadId
        )
    }

    private class SnapshotKey(
        private val api: GatewayApi,
        private val url: String,
        private val token: String,
        private val threadId: String
    ) {
        override fun equals(other: Any?): Boolean {
            return other is SnapshotKey &&
                api === other.api &&
                url == other.url &&
                token == other.token &&
                threadId == other.threadId
        }

        override fun hashCode(): Int {
            var result = System.identityHashCode(api)
            result = 31 * result + url.hashCode()
            result = 31 * result + token.hashCode()
            result = 31 * result + threadId.hashCode()
            return result
        }
    }
}
