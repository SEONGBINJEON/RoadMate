package com.roadmate.app.core

import org.junit.Assert.*
import org.junit.Test

class ResponseGateTest {
    @Test fun timeoutWinsAndLateAnswerIsIgnored() {
        val gate = ResponseGate()
        val request = gate.begin()
        assertTrue(gate.complete(request))
        assertFalse(gate.complete(request))
    }
    @Test fun cancelledPhaseCannotSpeakAgain() {
        val gate = ResponseGate()
        val request = gate.begin()
        gate.cancel()
        assertFalse(gate.complete(request))
    }
    @Test fun newerTurnCannotBeReplacedByOldResponse() {
        val gate = ResponseGate()
        val old = gate.begin()
        val current = gate.begin()
        assertFalse(gate.complete(old))
        assertTrue(gate.complete(current))
    }
}
