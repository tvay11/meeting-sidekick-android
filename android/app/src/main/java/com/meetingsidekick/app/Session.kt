package com.meetingsidekick.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SessionState(
    val active: Boolean = false,
    val busy: Boolean = false,
    val status: String = "Ready when you are",
    val transcript: String = "",
    val level: Float = 0f,
    val error: String? = null,
    val lagged: Boolean = false,
)

object Session {
    private val mutable = MutableStateFlow(SessionState())
    val state = mutable.asStateFlow()
    fun update(change: (SessionState) -> SessionState) = mutable.update(change)
    fun append(text: String) = update { it.copy(transcript = Transcript.merge(it.transcript, text)) }
    fun clear() { if (!mutable.value.active && !mutable.value.busy) mutable.value = SessionState() }
}
