package com.codex.mobilecontrol.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileModelsTest {

    @Test
    fun mobileThreadStatusFallsBackToIdleForUnknownWireValue() {
        val status = MobileThreadStatus.fromWireValue("unknown")
        assertEquals(MobileThreadStatus.IDLE, status)
    }

    @Test
    fun threadEventKindFallsBackToErrorForUnknownWireValue() {
        val kind = ThreadEventKind.fromWireValue("unknown")
        assertEquals(ThreadEventKind.ERROR, kind)
    }

    @Test
    fun threadMessageRoleFallsBackToSystemForUnknownWireValue() {
        val role = ThreadMessageRole.fromWireValue("unknown")
        assertEquals(ThreadMessageRole.SYSTEM, role)
    }

    @Test
    fun alertTriggerFallsBackToErrorForUnknownWireValue() {
        val trigger = AlertTrigger.fromWireValue("unknown")
        assertEquals(AlertTrigger.ERROR, trigger)
    }

    @Test
    fun alertTriggerIncludesMessageWireValueFromSharedContract() {
        assertEquals(AlertTrigger.MESSAGE, AlertTrigger.fromWireValue("message"))
        assertEquals("message", AlertTrigger.MESSAGE.wireValue)
    }

    @Test
    fun gatewayConnectionModeFallsBackToSocketForUnknownWireValue() {
        assertEquals(GatewayConnectionMode.SOCKET, GatewayConnectionMode.fromWireValue(null))
        assertEquals(GatewayConnectionMode.SOCKET, GatewayConnectionMode.fromWireValue("socket"))
        assertEquals("sse", GatewayConnectionMode.fromWireValue("sse").wireValue)
        assertEquals("sse", GatewayConnectionMode.fromWireValue("http").wireValue)
        assertEquals(GatewayConnectionMode.SOCKET, GatewayConnectionMode.fromWireValue("unknown"))
    }
}
