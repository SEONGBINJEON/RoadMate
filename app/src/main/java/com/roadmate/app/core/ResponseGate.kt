package com.roadmate.app.core

/** Accept a response or its timeout once; old sessions cannot speak after cancellation. */
class ResponseGate {
    private var generation = 0L
    private var completed = true
    fun begin(): Long { generation++; completed = false; return generation }
    fun cancel() { generation++; completed = true }
    fun complete(token: Long): Boolean {
        if (token != generation || completed) return false
        completed = true
        return true
    }
}
