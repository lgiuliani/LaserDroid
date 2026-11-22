package com.laserdroid

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel : ViewModel() {

    private val _log =
        MutableStateFlow(
            "LaserDroid ready."
        )

    val log: StateFlow<String> =
        _log.asStateFlow()

    fun appendLog(
        message: String
    ) {

        _log.value =
            if (_log.value.isBlank()) {

                message

            } else {

                "${_log.value}\n$message"
            }
    }

    fun replaceLog(
        message: String
    ) {

        _log.value = message
    }

    fun clearLog() {

        _log.value = ""
    }
}